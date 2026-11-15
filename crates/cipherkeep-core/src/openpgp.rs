//! Minimal OpenPGP (RFC 4880) symmetric decryption for password-protected
//! Proton Pass exports.
//!
//! CipherKeep only needs one OpenPGP operation: decrypting a `data.pgp`
//! payload with the passphrase Proton gave the user. That operation never
//! touches a public key, an RSA private key or a signature, so this module
//! implements exactly that subset on top of the same RustCrypto primitives the
//! vault already uses. Keeping the surface this small means the core links no
//! asymmetric OpenPGP code at all, and every import path stays inside the
//! audited crate set.
//!
//! # Supported subset
//!
//! * ASCII armor with or without armor headers, its CRC-24 trailer, or raw
//!   binary packets.
//! * `SKESK` version 4 with simple, salted and iterated/salted `S2K`
//!   (SHA-1, SHA-224, SHA-256, SHA-384, SHA-512), with or without an encrypted
//!   session key.
//! * `SEIPD` version 1 (AES-128, AES-192, AES-256 in CFB mode) with the SHA-1
//!   modification detection code, which is verified before any plaintext is
//!   returned.
//! * `Compressed Data` packets using the uncompressed, ZIP and ZLIB
//!   algorithms, and `Literal Data` packets.
//!
//! Everything else is rejected with [`ErrorCode::CorruptData`]. That includes
//! legacy symmetrically-encrypted packets without a modification detection
//! code and AEAD `SEIPD` version 2, because Proton never produces either and
//! accepting them would add unauthenticated decryption paths.
//!
//! # Bounds
//!
//! Packet counts, packet sizes, the number of session keys tried and the size
//! of decompressed payloads are all capped before work is performed, so a
//! malicious file cannot force unbounded allocation or CPU time.

use std::borrow::Cow;
use std::io::Read;

use cfb_mode::cipher::{AsyncStreamCipher, KeyIvInit};
use sha1::Digest;
use subtle::ConstantTimeEq;
use zeroize::Zeroizing;

use crate::{CoreError, ErrorCode, Result};

/// Maximum number of packets accepted in one message.
const MAX_PACKETS: usize = 4096;
/// Maximum accepted size of one packet body.
const MAX_PACKET_BYTES: usize = 64 * 1024 * 1024;
/// Maximum accepted size of a decompressed payload.
const MAX_INFLATED_BYTES: usize = 64 * 1024 * 1024;
/// Maximum nesting depth of compressed-data packets.
const MAX_COMPRESSION_DEPTH: usize = 4;
/// Maximum number of `SKESK` packets tried as passphrase candidates.
const MAX_SESSIONS: usize = 8;
/// Size of the modification detection code digest.
const MDC_DIGEST_LEN: usize = 20;
/// The modification detection code packet header (`D3 14`), which is covered
/// by the digest.
const MDC_HEADER: [u8; 2] = [0xD3, 0x14];

const ARMOR_BEGIN: &[u8] = b"-----BEGIN PGP MESSAGE-----";
const ARMOR_END: &[u8] = b"-----END PGP MESSAGE-----";

/// A redacted decryption failure.
const fn corrupt() -> CoreError {
    CoreError::new(ErrorCode::CorruptData)
}

/// Decrypt one passphrase-protected OpenPGP message.
///
/// The returned bytes are the literal payload, with armor, session-key,
/// integrity and compression layers removed. The caller owns them and must
/// treat them as sensitive plaintext.
///
/// # Errors
///
/// Returns [`ErrorCode::CorruptData`] for a wrong passphrase, corrupt armor,
/// an integrity failure, an unsupported algorithm or an unsupported packet
/// layout. The error never distinguishes those cases.
pub(crate) fn decrypt_symmetric_message(input: &[u8], passphrase: &str) -> Result<Vec<u8>> {
    if input.is_empty() || passphrase.is_empty() {
        return Err(corrupt());
    }
    let payload = armor_decode(input)?;
    let packets = parse_packets(payload.as_ref())?;
    let Some(encrypted) = packets
        .iter()
        .find(|packet| packet.tag == TAG_SEIPD)
        .map(|packet| packet.body.as_slice())
    else {
        return Err(corrupt());
    };
    let passphrase = passphrase.as_bytes();
    let mut sessions = 0usize;
    for packet in packets.iter().filter(|packet| packet.tag == TAG_SKESK) {
        if sessions >= MAX_SESSIONS {
            break;
        }
        sessions = sessions.saturating_add(1);
        let Some((algorithm, key)) = session_from_skesk(packet.body.as_slice(), passphrase) else {
            continue;
        };
        if let Some(content) = open_seipd(algorithm, &key, encrypted) {
            return extract_payload(&content, 0);
        }
    }
    Err(corrupt())
}

// ---------------------------------------------------------------------------
// Armor
// ---------------------------------------------------------------------------

/// Decode ASCII armor when present, otherwise borrow the raw packet stream.
fn armor_decode(input: &[u8]) -> Result<Cow<'_, [u8]>> {
    let Some(start) = find_marker(input, ARMOR_BEGIN) else {
        return Ok(Cow::Borrowed(input));
    };
    let mut encoded = Vec::new();
    let mut checksum = None;
    let mut in_body = false;
    for line in input[start..].split(|byte| *byte == b'\n').skip(1) {
        let line = trim_carriage_return(line);
        if line == ARMOR_END {
            break;
        }
        if line.is_empty() {
            in_body = true;
            continue;
        }
        if !in_body {
            // Armor headers ("Version: ...") precede a mandatory blank line.
            if line.contains(&b':') {
                continue;
            }
            in_body = true;
        }
        if line.len() == 5 && line[0] == b'=' {
            checksum = Some(decode_base64(&line[1..])?);
            continue;
        }
        if line.iter().any(|byte| !is_base64_byte(*byte)) {
            return Err(corrupt());
        }
        encoded.extend_from_slice(line);
    }
    if !in_body || encoded.is_empty() {
        return Err(corrupt());
    }
    let decoded = decode_base64(&encoded)?;
    if let Some(expected) = checksum {
        if expected.len() != 3 {
            return Err(corrupt());
        }
        if !bool::from(crc24(&decoded).ct_eq(expected.as_slice())) {
            return Err(corrupt());
        }
    }
    Ok(Cow::Owned(decoded))
}

/// Find the first occurrence of an armor marker.
fn find_marker(input: &[u8], marker: &[u8]) -> Option<usize> {
    input
        .windows(marker.len())
        .position(|window| window == marker)
}

/// Strip one trailing carriage return from an armor line.
fn trim_carriage_return(line: &[u8]) -> &[u8] {
    match line.last() {
        Some(b'\r') => &line[..line.len() - 1],
        _ => line,
    }
}

/// Whether a byte belongs to the standard base64 alphabet or its padding.
const fn is_base64_byte(byte: u8) -> bool {
    matches!(byte, b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'+' | b'/' | b'=')
}

/// Decode standard base64, tolerating a missing padding character.
fn decode_base64(encoded: &[u8]) -> Result<Vec<u8>> {
    if encoded.is_empty() {
        return Err(corrupt());
    }
    data_encoding::BASE64
        .decode(encoded)
        .or_else(|_| data_encoding::BASE64_NOPAD.decode(encoded))
        .map_err(|_| corrupt())
}

/// Compute the OpenPGP CRC-24 of a decoded armor body.
fn crc24(data: &[u8]) -> [u8; 3] {
    let mut crc: u32 = 0x00B7_04CE;
    for byte in data {
        crc ^= u32::from(*byte) << 16;
        for _ in 0..8 {
            crc <<= 1;
            if crc & 0x0100_0000 != 0 {
                crc ^= 0x0186_4CFB;
            }
        }
    }
    crc &= 0x00FF_FFFF;
    [
        u8::try_from(crc >> 16).unwrap_or(0),
        u8::try_from((crc >> 8) & 0xFF).unwrap_or(0),
        u8::try_from(crc & 0xFF).unwrap_or(0),
    ]
}

// ---------------------------------------------------------------------------
// Packets
// ---------------------------------------------------------------------------

const TAG_SKESK: u8 = 3;
const TAG_COMPRESSED: u8 = 8;
const TAG_LITERAL: u8 = 11;
const TAG_SEIPD: u8 = 18;

/// A packet body, borrowed from the input unless partial lengths forced a
/// concatenation.
enum Body<'a> {
    Borrowed(&'a [u8]),
    Owned(Vec<u8>),
}

impl Body<'_> {
    fn as_slice(&self) -> &[u8] {
        match self {
            Self::Borrowed(bytes) => bytes,
            Self::Owned(bytes) => bytes,
        }
    }
}

/// One parsed packet.
struct Packet<'a> {
    tag: u8,
    body: Body<'a>,
}

/// A cursor over a packet stream.
struct PacketReader<'a> {
    input: &'a [u8],
    offset: usize,
}

impl<'a> PacketReader<'a> {
    const fn new(input: &'a [u8]) -> Self {
        Self { input, offset: 0 }
    }

    /// Read the next packet, or `None` at the end of the stream.
    fn next_packet(&mut self) -> Result<Option<Packet<'a>>> {
        if self.offset >= self.input.len() {
            return Ok(None);
        }
        let ctb = self.byte()?;
        if ctb & 0x80 == 0 {
            return Err(corrupt());
        }
        if ctb & 0x40 != 0 {
            let tag = ctb & 0x3F;
            let body = self.new_format_body()?;
            return Ok(Some(Packet { tag, body }));
        }
        let tag = (ctb >> 2) & 0x0F;
        let body = match ctb & 0x03 {
            0 => {
                let length = usize::from(self.byte()?);
                self.take(length)?
            }
            1 => {
                let length = usize::from(self.two_octets()?);
                self.take(length)?
            }
            2 => {
                let length = self.four_octets()?;
                self.take(length)?
            }
            _ => {
                let rest = self.input.get(self.offset..).ok_or_else(corrupt)?;
                self.offset = self.input.len();
                Body::Borrowed(rest)
            }
        };
        Ok(Some(Packet { tag, body }))
    }

    /// Read a new-format body, joining partial-length chunks when needed.
    fn new_format_body(&mut self) -> Result<Body<'a>> {
        let first = self.byte()?;
        match first {
            0..=191 => self.take(usize::from(first)),
            192..=223 => {
                let second = self.byte()?;
                let length = ((usize::from(first) - 192) << 8) + usize::from(second) + 192;
                self.take(length)
            }
            255 => {
                let length = self.four_octets()?;
                self.take(length)
            }
            _ => {
                let mut joined = Vec::new();
                let mut length = partial_length(first)?;
                loop {
                    let chunk = self.take(length)?;
                    if joined.len().saturating_add(chunk.as_slice().len()) > MAX_PACKET_BYTES {
                        return Err(corrupt());
                    }
                    joined.extend_from_slice(chunk.as_slice());
                    let next = self.byte()?;
                    match next {
                        0..=191 => {
                            let tail = self.take(usize::from(next))?;
                            joined.extend_from_slice(tail.as_slice());
                            return Ok(Body::Owned(joined));
                        }
                        192..=223 => {
                            let second = self.byte()?;
                            let rest = ((usize::from(next) - 192) << 8) + usize::from(second) + 192;
                            let tail = self.take(rest)?;
                            joined.extend_from_slice(tail.as_slice());
                            return Ok(Body::Owned(joined));
                        }
                        255 => {
                            let rest = self.four_octets()?;
                            let tail = self.take(rest)?;
                            joined.extend_from_slice(tail.as_slice());
                            return Ok(Body::Owned(joined));
                        }
                        _ => length = partial_length(next)?,
                    }
                }
            }
        }
    }

    fn byte(&mut self) -> Result<u8> {
        let byte = *self.input.get(self.offset).ok_or_else(corrupt)?;
        self.offset = self.offset.saturating_add(1);
        Ok(byte)
    }

    fn two_octets(&mut self) -> Result<u16> {
        let high = u16::from(self.byte()?);
        let low = u16::from(self.byte()?);
        Ok((high << 8) | low)
    }

    fn four_octets(&mut self) -> Result<usize> {
        let first = usize::from(self.byte()?);
        let second = usize::from(self.byte()?);
        let third = usize::from(self.byte()?);
        let fourth = usize::from(self.byte()?);
        let length = (first << 24) | (second << 16) | (third << 8) | fourth;
        if length > MAX_PACKET_BYTES {
            return Err(corrupt());
        }
        Ok(length)
    }

    fn take(&mut self, length: usize) -> Result<Body<'a>> {
        if length > MAX_PACKET_BYTES {
            return Err(corrupt());
        }
        let end = self.offset.checked_add(length).ok_or_else(corrupt)?;
        let bytes = self.input.get(self.offset..end).ok_or_else(corrupt)?;
        self.offset = end;
        Ok(Body::Borrowed(bytes))
    }
}

/// Decode a partial body length octet into its chunk size.
fn partial_length(octet: u8) -> Result<usize> {
    let exponent = octet & 0x1F;
    let length = 1usize
        .checked_shl(u32::from(exponent))
        .ok_or_else(corrupt)?;
    if length > MAX_PACKET_BYTES {
        return Err(corrupt());
    }
    Ok(length)
}

/// Parse every packet in a stream.
fn parse_packets(input: &[u8]) -> Result<Vec<Packet<'_>>> {
    let mut packets = Vec::new();
    let mut reader = PacketReader::new(input);
    while let Some(packet) = reader.next_packet()? {
        if packets.len() >= MAX_PACKETS {
            return Err(corrupt());
        }
        packets.push(packet);
    }
    Ok(packets)
}

// ---------------------------------------------------------------------------
// String-to-key
// ---------------------------------------------------------------------------

/// Hash algorithms accepted for the string-to-key specifier.
#[derive(Clone, Copy)]
enum HashAlgorithm {
    Sha1,
    Sha224,
    Sha256,
    Sha384,
    Sha512,
}

impl HashAlgorithm {
    fn from_id(id: u8) -> Result<Self> {
        match id {
            2 => Ok(Self::Sha1),
            11 => Ok(Self::Sha224),
            8 => Ok(Self::Sha256),
            9 => Ok(Self::Sha384),
            10 => Ok(Self::Sha512),
            _ => Err(corrupt()),
        }
    }

    fn hasher(self) -> Hasher {
        match self {
            Self::Sha1 => Hasher::Sha1(sha1::Sha1::new()),
            Self::Sha224 => Hasher::Sha224(sha2::Sha224::new()),
            Self::Sha256 => Hasher::Sha256(sha2::Sha256::new()),
            Self::Sha384 => Hasher::Sha384(sha2::Sha384::new()),
            Self::Sha512 => Hasher::Sha512(sha2::Sha512::new()),
        }
    }
}

/// A streaming hasher over the accepted hash algorithms.
enum Hasher {
    Sha1(sha1::Sha1),
    Sha224(sha2::Sha224),
    Sha256(sha2::Sha256),
    Sha384(sha2::Sha384),
    Sha512(sha2::Sha512),
}

impl Hasher {
    fn update(&mut self, bytes: &[u8]) {
        match self {
            Self::Sha1(hasher) => Digest::update(hasher, bytes),
            Self::Sha224(hasher) => Digest::update(hasher, bytes),
            Self::Sha256(hasher) => Digest::update(hasher, bytes),
            Self::Sha384(hasher) => Digest::update(hasher, bytes),
            Self::Sha512(hasher) => Digest::update(hasher, bytes),
        }
    }

    fn finish(self) -> Vec<u8> {
        match self {
            Self::Sha1(hasher) => hasher.finalize().to_vec(),
            Self::Sha224(hasher) => hasher.finalize().to_vec(),
            Self::Sha256(hasher) => hasher.finalize().to_vec(),
            Self::Sha384(hasher) => hasher.finalize().to_vec(),
            Self::Sha512(hasher) => hasher.finalize().to_vec(),
        }
    }
}

/// The salt/count shape of a string-to-key specifier.
enum S2kMode {
    Simple,
    Salted([u8; 8]),
    Iterated { salt: [u8; 8], count: usize },
}

/// A parsed string-to-key specifier.
struct S2k {
    hash: HashAlgorithm,
    mode: S2kMode,
}

impl S2k {
    /// Parse a specifier and return it with the number of bytes consumed.
    fn parse(input: &[u8]) -> Result<(Self, usize)> {
        let kind = *input.first().ok_or_else(corrupt)?;
        let hash = HashAlgorithm::from_id(*input.get(1).ok_or_else(corrupt)?)?;
        match kind {
            0 => Ok((
                Self {
                    hash,
                    mode: S2kMode::Simple,
                },
                2,
            )),
            1 => {
                let salt = salt(input.get(2..10))?;
                Ok((
                    Self {
                        hash,
                        mode: S2kMode::Salted(salt),
                    },
                    10,
                ))
            }
            3 => {
                let salt = salt(input.get(2..10))?;
                let coded = *input.get(10).ok_or_else(corrupt)?;
                let count = (16usize + usize::from(coded & 0x0F)) << (u32::from(coded >> 4) + 6);
                Ok((
                    Self {
                        hash,
                        mode: S2kMode::Iterated { salt, count },
                    },
                    11,
                ))
            }
            _ => Err(corrupt()),
        }
    }

    /// Derive a symmetric key of `key_len` bytes from the passphrase.
    fn derive(&self, passphrase: &[u8], key_len: usize) -> Zeroizing<Vec<u8>> {
        let mut material = Zeroizing::new(Vec::with_capacity(passphrase.len().saturating_add(8)));
        let count = match &self.mode {
            S2kMode::Simple => {
                material.extend_from_slice(passphrase);
                material.len()
            }
            S2kMode::Salted(salt) => {
                material.extend_from_slice(salt);
                material.extend_from_slice(passphrase);
                material.len()
            }
            S2kMode::Iterated { salt, count } => {
                material.extend_from_slice(salt);
                material.extend_from_slice(passphrase);
                *count
            }
        };
        let mut key = Zeroizing::new(Vec::with_capacity(key_len));
        if material.is_empty() {
            return key;
        }
        let mut round = 0usize;
        while key.len() < key_len {
            let mut hasher = self.hash.hasher();
            for _ in 0..round {
                hasher.update(&[0u8]);
            }
            let mut remaining = count;
            while remaining > 0 {
                let take = remaining.min(material.len());
                hasher.update(&material[..take]);
                remaining -= take;
            }
            let digest = hasher.finish();
            let needed = key_len - key.len();
            key.extend_from_slice(&digest[..needed.min(digest.len())]);
            round = round.saturating_add(1);
            if round > key_len {
                break;
            }
        }
        key
    }
}

/// Read an eight-octet salt, rejecting a short buffer.
fn salt(bytes: Option<&[u8]>) -> Result<[u8; 8]> {
    let bytes = bytes.ok_or_else(corrupt)?;
    let mut salt = [0u8; 8];
    salt.copy_from_slice(bytes);
    Ok(salt)
}

// ---------------------------------------------------------------------------
// Session keys and the encrypted payload
// ---------------------------------------------------------------------------

/// Symmetric key length for a cipher algorithm identifier.
const fn key_length(algorithm: u8) -> Option<usize> {
    match algorithm {
        7 => Some(16),
        8 => Some(24),
        9 => Some(32),
        _ => None,
    }
}

/// CFB block size for a cipher algorithm identifier.
const fn block_length(algorithm: u8) -> Option<usize> {
    match algorithm {
        7..=9 => Some(16),
        _ => None,
    }
}

/// Derive the session key offered by one `SKESK` packet.
fn session_from_skesk(body: &[u8], passphrase: &[u8]) -> Option<(u8, Zeroizing<Vec<u8>>)> {
    if *body.first()? != 4 {
        return None;
    }
    let algorithm = *body.get(1)?;
    let (s2k, consumed) = S2k::parse(body.get(2..)?).ok()?;
    let derived = s2k.derive(passphrase, key_length(algorithm)?);
    let encrypted = body.get(2 + consumed..)?;
    if encrypted.is_empty() {
        return Some((algorithm, derived));
    }
    let mut plaintext = Zeroizing::new(encrypted.to_vec());
    cfb_decrypt(algorithm, &derived, &mut plaintext)?;
    let (session_algorithm, session) = plaintext.split_first()?;
    let expected = key_length(*session_algorithm)?;
    if session.len() != expected {
        return None;
    }
    Some((*session_algorithm, Zeroizing::new(session.to_vec())))
}

/// Decrypt an in-place CFB stream with an all-zero initialisation vector.
///
/// OpenPGP uses CFB with a zero IV for both the session-key block of a `SKESK`
/// packet and the body of a `SEIPD` packet; the latter carries its own random
/// prefix block instead of relying on a random IV.
fn cfb_decrypt(algorithm: u8, key: &[u8], data: &mut [u8]) -> Option<()> {
    if key.len() != key_length(algorithm)? {
        return None;
    }
    let iv = [0u8; 16];
    match algorithm {
        7 => cfb_mode::Decryptor::<aes::Aes128>::new_from_slices(key, &iv)
            .ok()?
            .decrypt(data),
        8 => cfb_mode::Decryptor::<aes::Aes192>::new_from_slices(key, &iv)
            .ok()?
            .decrypt(data),
        9 => cfb_mode::Decryptor::<aes::Aes256>::new_from_slices(key, &iv)
            .ok()?
            .decrypt(data),
        _ => return None,
    }
    Some(())
}

/// Verify the modification detection code and return the enclosed packets.
fn open_seipd(algorithm: u8, key: &[u8], body: &[u8]) -> Option<Zeroizing<Vec<u8>>> {
    if *body.first()? != 1 {
        return None;
    }
    let block = block_length(algorithm)?;
    // The plaintext opens with a random prefix of one cipher block plus two
    // octets, exactly as in the older Symmetrically Encrypted Data packet.
    // The prefix is covered by the modification detection code and discarded
    // here.
    let prefix = block.checked_add(2)?;
    let ciphertext = body.get(1..)?;
    if ciphertext.len() < prefix + MDC_HEADER.len() + MDC_DIGEST_LEN {
        return None;
    }
    let mut plaintext = Zeroizing::new(ciphertext.to_vec());
    cfb_decrypt(algorithm, key, &mut plaintext)?;
    let split = plaintext.len() - MDC_DIGEST_LEN;
    let (covered, digest) = plaintext.split_at(split);
    if !covered.ends_with(&MDC_HEADER) {
        return None;
    }
    let computed = sha1::Sha1::digest(covered);
    if !bool::from(computed.as_slice().ct_eq(digest)) {
        return None;
    }
    let content = covered.get(prefix..covered.len() - MDC_HEADER.len())?;
    Some(Zeroizing::new(content.to_vec()))
}

/// Extract the literal payload from decrypted, possibly compressed, packets.
fn extract_payload(content: &[u8], depth: usize) -> Result<Vec<u8>> {
    if depth > MAX_COMPRESSION_DEPTH {
        return Err(corrupt());
    }
    let Ok(packets) = parse_packets(content) else {
        return Ok(raw_payload(content)?.to_vec());
    };
    if let Some(packet) = packets.iter().find(|packet| packet.tag == TAG_LITERAL) {
        return literal_data(packet.body.as_slice());
    }
    if let Some(packet) = packets.iter().find(|packet| packet.tag == TAG_COMPRESSED) {
        let inflated = inflate(packet.body.as_slice())?;
        return extract_payload(&inflated, depth + 1);
    }
    Ok(raw_payload(content)?.to_vec())
}

/// Accept a bare JSON document from producers that skip the literal packet.
fn raw_payload(content: &[u8]) -> Result<&[u8]> {
    let trimmed = content.trim_ascii_start();
    match trimmed.first() {
        Some(b'{' | b'[') => Ok(content),
        _ => Err(corrupt()),
    }
}

/// Strip the literal-data header from a packet body.
fn literal_data(body: &[u8]) -> Result<Vec<u8>> {
    let name_length = usize::from(*body.get(1).ok_or_else(corrupt)?);
    let start = 2 + name_length + 4;
    body.get(start..).map(<[u8]>::to_vec).ok_or_else(corrupt)
}

/// Decompress a `Compressed Data` packet body.
fn inflate(body: &[u8]) -> Result<Vec<u8>> {
    let (algorithm, data) = body.split_first().ok_or_else(corrupt)?;
    let mut out = Vec::new();
    match algorithm {
        0 => {
            if data.len() > MAX_INFLATED_BYTES {
                return Err(corrupt());
            }
            out.extend_from_slice(data);
        }
        1 => read_bounded(flate2::read::DeflateDecoder::new(data), &mut out)?,
        2 => read_bounded(flate2::read::ZlibDecoder::new(data), &mut out)?,
        _ => return Err(corrupt()),
    }
    Ok(out)
}

/// Read a decompressor into `out`, stopping one byte past the size budget.
fn read_bounded<R: Read>(reader: R, out: &mut Vec<u8>) -> Result<()> {
    let limit = u64::try_from(MAX_INFLATED_BYTES)
        .unwrap_or(u64::MAX)
        .saturating_add(1);
    reader.take(limit).read_to_end(out).map_err(|_| corrupt())?;
    if out.len() > MAX_INFLATED_BYTES {
        return Err(corrupt());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc24_matches_the_published_check_value() {
        assert_eq!(crc24(b"123456789"), [0x21, 0xCF, 0x02]);
    }

    #[test]
    fn iterated_s2k_count_decoding_matches_the_specification() {
        // 0x60 selects (16 + 0) << (6 + 6) = 65536 bytes.
        let (_, consumed) = S2k::parse(&[3, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0x60]).expect("parse");
        assert_eq!(consumed, 11);
        let (s2k, _) = S2k::parse(&[3, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0x60]).expect("parse");
        match s2k.mode {
            S2kMode::Iterated { count, .. } => assert_eq!(count, 65_536),
            _ => panic!("expected an iterated specifier"),
        }
    }

    #[test]
    fn partial_lengths_are_bounded() {
        assert_eq!(partial_length(224).expect("smallest"), 1);
        assert_eq!(partial_length(230).expect("one byte"), 64);
        assert!(partial_length(255).is_err());
    }

    #[test]
    fn short_s2k_specifiers_are_rejected() {
        assert!(S2k::parse(&[]).is_err());
        assert!(S2k::parse(&[3, 8]).is_err());
        assert!(S2k::parse(&[3, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0x60]).is_err());
    }

    #[test]
    fn malformed_packet_headers_are_rejected() {
        assert!(parse_packets(&[0x00]).is_err());
        assert!(parse_packets(&[0xC3]).is_err());
    }
}
