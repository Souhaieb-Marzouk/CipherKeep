//! Bias-free password and passphrase generation.
//!
//! Randomness comes from the operating system CSPRNG through [`getrandom`].
//! There is no seeded fallback, no `Math.random` equivalent and no logging of
//! generated values. Character selection uses rejection sampling so modulo
//! reduction cannot bias the result.

use std::sync::OnceLock;

use crate::error::{CoreError, ErrorCode, Result};
use crate::secret::SecretString;

/// Number of words in the bundled EFF short wordlist.
pub const EFF_WORDLIST_LEN: usize = 1296;

/// Maximum generated password length.
pub const MAX_PASSWORD_LEN: usize = 128;
/// Minimum generated password length.
pub const MIN_PASSWORD_LEN: usize = 8;
/// Maximum passphrase word count.
pub const MAX_PASSPHRASE_WORDS: usize = 12;
/// Minimum passphrase word count.
pub const MIN_PASSPHRASE_WORDS: usize = 3;

const LOWER: &[u8] = b"abcdefghijklmnopqrstuvwxyz";
const UPPER: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const DIGITS: &[u8] = b"0123456789";
const SYMBOLS: &[u8] = b"!@#$%^&*()-_=+[]{};:,.?";

/// Password generation options.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PasswordPolicy {
    /// Total number of characters.
    pub length: usize,
    /// Include lowercase letters.
    pub lowercase: bool,
    /// Include uppercase letters.
    pub uppercase: bool,
    /// Include digits.
    pub digits: bool,
    /// Include punctuation symbols.
    pub symbols: bool,
    /// Remove commonly confused characters: I, l, 1, O, 0, o.
    pub exclude_ambiguous: bool,
}

impl Default for PasswordPolicy {
    fn default() -> Self {
        Self {
            length: 20,
            lowercase: true,
            uppercase: true,
            digits: true,
            symbols: true,
            exclude_ambiguous: false,
        }
    }
}

/// Passphrase generation options.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PassphrasePolicy {
    /// Number of words, from 3 to 12.
    pub words: usize,
    /// Separator inserted between words.
    pub separator: char,
}

impl Default for PassphrasePolicy {
    fn default() -> Self {
        Self {
            words: 6,
            separator: '-',
        }
    }
}

/// Plain-language strength label derived from entropy bits.
#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
pub enum StrengthLabel {
    /// Less than 40 bits.
    VeryWeak,
    /// Less than 60 bits.
    Weak,
    /// Less than 80 bits.
    Fair,
    /// Less than 100 bits.
    Strong,
    /// At least 100 bits.
    VeryStrong,
}

impl StrengthLabel {
    /// Human-readable label.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::VeryWeak => "Very weak",
            Self::Weak => "Weak",
            Self::Fair => "Fair",
            Self::Strong => "Strong",
            Self::VeryStrong => "Very strong",
        }
    }

    /// Derive a label from an honest entropy estimate.
    #[must_use]
    pub fn from_bits(bits: f64) -> Self {
        if bits < 40.0 {
            Self::VeryWeak
        } else if bits < 60.0 {
            Self::Weak
        } else if bits < 80.0 {
            Self::Fair
        } else if bits < 100.0 {
            Self::Strong
        } else {
            Self::VeryStrong
        }
    }
}

/// A generated value and its entropy estimate.
///
/// The value is zeroized when this structure is dropped. Debug output never
/// contains the value.
pub struct GeneratedSecret {
    value: SecretString,
    entropy_bits: f64,
    strength: StrengthLabel,
}

impl GeneratedSecret {
    /// Expose the generated value only at the point of use.
    #[must_use]
    pub fn expose_secret(&self) -> &str {
        self.value.expose_secret()
    }

    /// Entropy estimate in bits based on the actual alphabet or wordlist.
    #[must_use]
    pub const fn entropy_bits(&self) -> f64 {
        self.entropy_bits
    }

    /// Plain-language label derived from the entropy estimate.
    #[must_use]
    pub const fn strength(&self) -> StrengthLabel {
        self.strength
    }
}

impl core::fmt::Debug for GeneratedSecret {
    fn fmt(&self, formatter: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        formatter
            .debug_struct("GeneratedSecret")
            .field("entropy_bits", &self.entropy_bits)
            .field("strength", &self.strength)
            .finish_non_exhaustive()
    }
}

/// Generate a random password using rejection sampling.
///
/// The generator guarantees that every selected class appears at least once by
/// rejecting samples that miss a class. The policy must leave room for all
/// selected classes.
///
/// # Errors
///
/// Returns [`ErrorCode::PolicyRejected`] for an impossible policy and
/// [`ErrorCode::Internal`] if the OS entropy source fails.
pub fn generate_password(policy: &PasswordPolicy) -> Result<GeneratedSecret> {
    if !(MIN_PASSWORD_LEN..=MAX_PASSWORD_LEN).contains(&policy.length) {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    let classes = selected_classes(policy);
    if classes.is_empty() || policy.length < classes.len() {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    let alphabet = alphabet(policy);
    if alphabet.len() < 2 {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }

    for _ in 0..256 {
        let mut candidate = Vec::with_capacity(policy.length);
        for _ in 0..policy.length {
            let index = random_index(alphabet.len())?;
            candidate.push(alphabet[index]);
        }
        if classes
            .iter()
            .all(|class| candidate.iter().any(|byte| class.contains(byte)))
        {
            let value = core::str::from_utf8(&candidate)
                .map_err(|_| CoreError::new(ErrorCode::Internal))?;
            let bits = entropy(policy.length, alphabet.len());
            return Ok(GeneratedSecret {
                value: SecretString::new(value),
                entropy_bits: bits,
                strength: StrengthLabel::from_bits(bits),
            });
        }
    }
    Err(CoreError::new(ErrorCode::Internal))
}

/// Generate an EFF-style passphrase.
///
/// # Errors
///
/// Returns [`ErrorCode::PolicyRejected`] for an invalid word count or
/// separator and [`ErrorCode::Internal`] if the OS entropy source fails.
pub fn generate_passphrase(policy: &PassphrasePolicy) -> Result<GeneratedSecret> {
    if !(MIN_PASSPHRASE_WORDS..=MAX_PASSPHRASE_WORDS).contains(&policy.words)
        || policy.separator.is_control()
    {
        return Err(CoreError::new(ErrorCode::PolicyRejected));
    }
    let words = eff_words();
    let mut value = String::new();
    for index in 0..policy.words {
        if index != 0 {
            value.push(policy.separator);
        }
        let selected = random_index(words.len())?;
        value.push_str(words[selected]);
    }
    let bits = entropy(policy.words, words.len());
    Ok(GeneratedSecret {
        value: SecretString::new(&value),
        entropy_bits: bits,
        strength: StrengthLabel::from_bits(bits),
    })
}

/// Number of words in the bundled EFF list, parsed from the static resource.
#[must_use]
pub fn wordlist_len() -> usize {
    eff_words().len()
}

/// Check that the bundled wordlist is non-empty and contains no duplicates.
#[must_use]
pub fn wordlist_is_valid() -> bool {
    let words = eff_words();
    if words.len() != EFF_WORDLIST_LEN {
        return false;
    }
    let mut sorted = words.to_vec();
    sorted.sort_unstable();
    sorted.windows(2).all(|pair| pair[0] != pair[1])
}

fn selected_classes(policy: &PasswordPolicy) -> Vec<&'static [u8]> {
    let mut classes = Vec::new();
    if policy.lowercase {
        classes.push(LOWER);
    }
    if policy.uppercase {
        classes.push(UPPER);
    }
    if policy.digits {
        classes.push(DIGITS);
    }
    if policy.symbols {
        classes.push(SYMBOLS);
    }
    classes
}

fn alphabet(policy: &PasswordPolicy) -> Vec<u8> {
    let mut bytes = Vec::new();
    for class in selected_classes(policy) {
        bytes.extend_from_slice(class);
    }
    if policy.exclude_ambiguous {
        bytes.retain(|byte| !b"Il1O0o".contains(byte));
    }
    bytes.sort_unstable();
    bytes.dedup();
    bytes
}

fn entropy(length: usize, alphabet_len: usize) -> f64 {
    (length as f64) * (alphabet_len as f64).log2()
}

fn random_index(upper: usize) -> Result<usize> {
    if upper == 0 {
        return Err(CoreError::new(ErrorCode::Internal));
    }
    let upper = u32::try_from(upper).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    let zone = u32::MAX - (u32::MAX % upper);
    for _ in 0..32 {
        let mut bytes = [0u8; 4];
        getrandom::getrandom(&mut bytes).map_err(|_| CoreError::new(ErrorCode::Internal))?;
        let value = u32::from_le_bytes(bytes);
        if value < zone {
            return Ok((value % upper) as usize);
        }
    }
    Err(CoreError::new(ErrorCode::Internal))
}

fn eff_words() -> &'static [&'static str] {
    static WORDS: OnceLock<Vec<&'static str>> = OnceLock::new();
    WORDS
        .get_or_init(|| {
            include_str!("../data/eff_short_wordlist_1.txt")
                .lines()
                .filter(|line| !line.starts_with('#') && !line.is_empty())
                .collect()
        })
        .as_slice()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn password_policy_is_met_across_ten_thousand_samples() {
        let policy = PasswordPolicy::default();
        for _ in 0..10_000 {
            let generated = generate_password(&policy).expect("generate");
            let value = generated.expose_secret();
            assert_eq!(value.len(), policy.length);
            assert!(value
                .chars()
                .any(|character| character.is_ascii_lowercase()));
            assert!(value
                .chars()
                .any(|character| character.is_ascii_uppercase()));
            assert!(value.chars().any(|character| character.is_ascii_digit()));
            assert!(value
                .chars()
                .any(|character| "!@#$%^&*()-_=+[]{};:,.?".contains(character)));
        }
    }

    #[test]
    fn ambiguous_characters_can_be_excluded() {
        let policy = PasswordPolicy {
            length: 64,
            exclude_ambiguous: true,
            ..PasswordPolicy::default()
        };
        for _ in 0..100 {
            let generated = generate_password(&policy).expect("generate");
            assert!(!generated
                .expose_secret()
                .chars()
                .any(|character| "Il1O0o".contains(character)));
        }
    }

    #[test]
    fn passphrases_use_requested_count_and_separator() {
        let policy = PassphrasePolicy {
            words: 6,
            separator: '.',
        };
        let generated = generate_passphrase(&policy).expect("generate");
        assert_eq!(generated.expose_secret().split('.').count(), 6);
        assert!((generated.entropy_bits() - 6.0 * 1296f64.log2()).abs() < f64::EPSILON);
        assert_eq!(generated.strength(), StrengthLabel::Fair);
    }

    #[test]
    fn entropy_uses_the_actual_alphabet() {
        let policy = PasswordPolicy {
            length: 20,
            lowercase: true,
            uppercase: false,
            digits: false,
            symbols: false,
            exclude_ambiguous: false,
        };
        let generated = generate_password(&policy).expect("generate");
        assert!((generated.entropy_bits() - 20.0 * 26f64.log2()).abs() < 1e-9);
        assert_eq!(generated.strength(), StrengthLabel::Strong);
    }

    #[test]
    fn wordlist_is_complete_and_unique() {
        assert_eq!(wordlist_len(), 1296);
        assert!(wordlist_is_valid());
    }

    #[test]
    fn impossible_policies_are_refused() {
        assert_eq!(
            generate_password(&PasswordPolicy {
                length: 2,
                ..PasswordPolicy::default()
            })
            .unwrap_err()
            .code(),
            ErrorCode::PolicyRejected
        );
        assert_eq!(
            generate_passphrase(&PassphrasePolicy {
                words: 2,
                separator: '-',
            })
            .unwrap_err()
            .code(),
            ErrorCode::PolicyRejected
        );
    }
}
