//! Family vault sharing with X25519 key re-wrapping.
//!
//! The owner wraps the 32-byte vault key to each member's X25519 public key
//! using ephemeral-static X25519, HKDF-SHA-256 and XChaCha20-Poly1305. Removing
//! a member rotates the vault key so future writes are unreadable to them; old
//! ciphertext they already hold remains readable, which the UI must state.

use std::collections::BTreeMap;

use chacha20poly1305::aead::{Aead, KeyInit, Payload};
use chacha20poly1305::{Key, XChaCha20Poly1305, XNonce};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroizing;

use crate::error::{CoreError, ErrorCode, Result};
use crate::keys::{VaultId, VaultKey, KEY_LEN};
use crate::secret::SecretBytes;

/// Member role.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Role {
    /// Can invite, revoke and rotate.
    Owner,
    /// Can read and write items.
    Editor,
    /// Read-only.
    Viewer,
}

impl Role {
    /// Whether the role permits writes.
    #[must_use]
    pub const fn can_write(self) -> bool {
        matches!(self, Self::Owner | Self::Editor)
    }
}

/// A family member.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Member {
    /// Stable member id.
    pub id: String,
    /// X25519 public key.
    pub public_key: [u8; 32],
    /// Role.
    pub role: Role,
}

impl Member {
    /// SHA-256 fingerprint of the public key, grouped for display.
    #[must_use]
    pub fn fingerprint(&self) -> String {
        let digest = Sha256::digest(self.public_key);
        digest
            .iter()
            .take(8)
            .map(|byte| format!("{byte:02x}"))
            .collect::<Vec<_>>()
            .join(" ")
    }
}

/// Wrapped vault key for one member.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct WrappedKey {
    /// Member id.
    pub member_id: String,
    /// Ephemeral X25519 public key.
    pub ephemeral_public: [u8; 32],
    /// XChaCha20 nonce.
    pub nonce: [u8; 24],
    /// Encrypted 32-byte vault key.
    pub ciphertext: Vec<u8>,
}

/// Audit event without secrets.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum AuditEvent {
    /// Member added.
    Invited {
        /// Invited member id.
        member_id: String,
        /// Initial role.
        role: Role,
    },
    /// Role changed.
    RoleChanged {
        /// Member id.
        member_id: String,
        /// New role.
        role: Role,
    },
    /// Member removed and key rotated.
    Revoked {
        /// Revoked member id.
        member_id: String,
        /// Rotation epoch after revocation.
        epoch: u64,
    },
}

/// Generate a member identity keypair.
///
/// # Errors
///
/// Returns an error if the OS CSPRNG fails.
pub fn generate_identity(id: &str, role: Role) -> Result<([u8; 32], Member)> {
    let mut secret = [0u8; 32];
    getrandom::getrandom(&mut secret).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    let static_secret = StaticSecret::from(secret);
    let member = Member {
        id: id.to_string(),
        public_key: PublicKey::from(&static_secret).to_bytes(),
        role,
    };
    Ok((secret, member))
}

/// Wrap an arbitrary 32-byte vault key to a member.
///
/// # Errors
///
/// Returns an error for a wrong key length or RNG failure.
pub fn wrap_secret(vault_id: VaultId, vault_key: &[u8; 32], member: &Member) -> Result<WrappedKey> {
    let mut ephemeral_bytes = [0u8; 32];
    getrandom::getrandom(&mut ephemeral_bytes).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    let ephemeral = StaticSecret::from(ephemeral_bytes);
    let shared = ephemeral.diffie_hellman(&PublicKey::from(member.public_key));
    let key = derive_wrap_key(shared.as_bytes(), &member.id, &vault_id)?;
    let cipher = XChaCha20Poly1305::new(Key::from_slice(&key));
    let mut nonce = [0u8; 24];
    getrandom::getrandom(&mut nonce).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    let aad = aad(&member.id, &vault_id, member.role);
    let ciphertext = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: vault_key,
                aad: &aad,
            },
        )
        .map_err(|_| CoreError::new(ErrorCode::Internal))?;
    Ok(WrappedKey {
        member_id: member.id.clone(),
        ephemeral_public: PublicKey::from(&ephemeral).to_bytes(),
        nonce,
        ciphertext,
    })
}

/// Unwrap a member's vault key.
///
/// # Errors
///
/// Rejects a wrong key, wrong member, wrong role or tampered envelope.
pub fn unwrap_secret(
    vault_id: VaultId,
    wrapped: &WrappedKey,
    role: Role,
    secret: &StaticSecret,
) -> Result<[u8; 32]> {
    let shared = secret.diffie_hellman(&PublicKey::from(wrapped.ephemeral_public));
    let key = derive_wrap_key(shared.as_bytes(), &wrapped.member_id, &vault_id)?;
    let cipher = XChaCha20Poly1305::new(Key::from_slice(&key));
    let aad = aad(&wrapped.member_id, &vault_id, role);
    let plaintext = cipher
        .decrypt(
            XNonce::from_slice(&wrapped.nonce),
            Payload {
                msg: &wrapped.ciphertext,
                aad: &aad,
            },
        )
        .map_err(|_| CoreError::new(ErrorCode::AuthenticationFailed))?;
    plaintext
        .try_into()
        .map_err(|_| CoreError::new(ErrorCode::CorruptData))
}

/// Family sharing state.
pub struct FamilyVault {
    vault: VaultKey,
    owner_id: String,
    members: BTreeMap<String, Member>,
    wrapped: BTreeMap<String, WrappedKey>,
    audit: Vec<AuditEvent>,
    epoch: u64,
}

impl FamilyVault {
    /// Create a family vault with one owner.
    ///
    /// # Errors
    ///
    /// Rejects a non-owner owner record.
    pub fn new(vault: VaultKey, owner: Member) -> Result<Self> {
        if owner.role != Role::Owner {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let owner_id = owner.id.clone();
        let wrapped = wrap_key(&vault, &owner)?;
        let mut members = BTreeMap::new();
        members.insert(owner.id.clone(), owner);
        let mut wrapped_map = BTreeMap::new();
        wrapped_map.insert(wrapped.member_id.clone(), wrapped);
        Ok(Self {
            vault,
            owner_id,
            members,
            wrapped: wrapped_map,
            audit: Vec::new(),
            epoch: 1,
        })
    }

    /// Current rotation epoch.
    #[must_use]
    pub fn epoch(&self) -> u64 {
        self.epoch
    }

    /// Members sorted by id.
    #[must_use]
    pub fn members(&self) -> Vec<&Member> {
        self.members.values().collect()
    }

    /// Audit events.
    #[must_use]
    pub fn audit(&self) -> &[AuditEvent] {
        &self.audit
    }

    /// Invite a member. Only the owner may invite.
    ///
    /// # Errors
    ///
    /// Rejects non-owners, duplicate member ids and empty ids.
    pub fn invite(&mut self, requester: &str, member: Member) -> Result<()> {
        self.require_owner(requester)?;
        if member.id.is_empty() || self.members.contains_key(&member.id) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        if member.role == Role::Owner {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let wrapped = wrap_key(&self.vault, &member)?;
        self.members.insert(member.id.clone(), member.clone());
        self.wrapped.insert(member.id.clone(), wrapped);
        self.audit.push(AuditEvent::Invited {
            member_id: member.id,
            role: member.role,
        });
        Ok(())
    }

    /// Change a member role. Only the owner may do so.
    ///
    /// # Errors
    ///
    /// Rejects non-owners and unknown/owner members.
    pub fn set_role(&mut self, requester: &str, member_id: &str, role: Role) -> Result<()> {
        self.require_owner(requester)?;
        if role == Role::Owner || member_id == self.owner_id {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        let member = self
            .members
            .get_mut(member_id)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        member.role = role;
        self.audit.push(AuditEvent::RoleChanged {
            member_id: member_id.to_string(),
            role,
        });
        Ok(())
    }

    /// Revoke a member and rotate the vault key.
    ///
    /// # Errors
    ///
    /// Rejects non-owners, the owner and unknown members.
    pub fn revoke(&mut self, requester: &str, member_id: &str) -> Result<()> {
        self.require_owner(requester)?;
        if member_id == self.owner_id || !self.members.contains_key(member_id) {
            return Err(CoreError::new(ErrorCode::InvalidArgument));
        }
        self.members.remove(member_id);
        self.wrapped.remove(member_id);
        self.vault = VaultKey::generate()?;
        self.epoch += 1;
        let remaining: Vec<Member> = self.members.values().cloned().collect();
        self.wrapped.clear();
        for member in remaining {
            let wrapped = wrap_key(&self.vault, &member)?;
            self.wrapped.insert(member.id.clone(), wrapped);
        }
        self.audit.push(AuditEvent::Revoked {
            member_id: member_id.to_string(),
            epoch: self.epoch,
        });
        Ok(())
    }

    /// Unwrap the current vault key as a member.
    ///
    /// # Errors
    ///
    /// Rejects unknown members, wrong keys and a revoked member.
    pub fn unwrap(&self, member_id: &str, secret: &StaticSecret) -> Result<VaultKey> {
        let member = self
            .members
            .get(member_id)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        let wrapped = self
            .wrapped
            .get(member_id)
            .ok_or_else(|| CoreError::new(ErrorCode::NotFound))?;
        if PublicKey::from(secret).as_bytes() != &member.public_key {
            return Err(CoreError::new(ErrorCode::AuthenticationFailed));
        }
        let shared = secret.diffie_hellman(&PublicKey::from(wrapped.ephemeral_public));
        let key = derive_wrap_key(shared.as_bytes(), member_id, &self.vault.id())?;
        let cipher = XChaCha20Poly1305::new(Key::from_slice(&key));
        let aad = aad(member_id, &self.vault.id(), member.role);
        let plaintext = cipher
            .decrypt(
                XNonce::from_slice(&wrapped.nonce),
                Payload {
                    msg: &wrapped.ciphertext,
                    aad: &aad,
                },
            )
            .map_err(|_| CoreError::new(ErrorCode::AuthenticationFailed))?;
        VaultKey::from_parts(self.vault.id(), SecretBytes::from_slice(&plaintext))
    }

    /// Whether a member may write.
    #[must_use]
    pub fn can_write(&self, member_id: &str) -> bool {
        self.members
            .get(member_id)
            .is_some_and(|member| member.role.can_write())
    }

    fn require_owner(&self, requester: &str) -> Result<()> {
        if requester != self.owner_id {
            return Err(CoreError::new(ErrorCode::PolicyRejected));
        }
        Ok(())
    }
}

fn wrap_key(vault: &VaultKey, member: &Member) -> Result<WrappedKey> {
    let mut ephemeral_bytes = [0u8; 32];
    getrandom::getrandom(&mut ephemeral_bytes).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    let ephemeral = StaticSecret::from(ephemeral_bytes);
    let shared = ephemeral.diffie_hellman(&PublicKey::from(member.public_key));
    let key = derive_wrap_key(shared.as_bytes(), &member.id, &vault.id())?;
    let cipher = XChaCha20Poly1305::new(Key::from_slice(&key));
    let mut nonce = [0u8; 24];
    getrandom::getrandom(&mut nonce).map_err(|_| CoreError::new(ErrorCode::Internal))?;
    let aad = aad(&member.id, &vault.id(), member.role);
    let plaintext = Zeroizing::new(vault.secret().expose_secret().to_vec());
    let ciphertext = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: &plaintext,
                aad: &aad,
            },
        )
        .map_err(|_| CoreError::new(ErrorCode::Internal))?;
    Ok(WrappedKey {
        member_id: member.id.clone(),
        ephemeral_public: PublicKey::from(&ephemeral).to_bytes(),
        nonce,
        ciphertext,
    })
}

fn derive_wrap_key(
    shared: &[u8; 32],
    member_id: &str,
    vault_id: &VaultId,
) -> Result<[u8; KEY_LEN]> {
    let hkdf = Hkdf::<Sha256>::new(Some(vault_id.as_bytes()), shared);
    let mut info = Vec::with_capacity(member_id.len() + 16);
    info.extend_from_slice(b"CipherKeep/fam1");
    info.extend_from_slice(member_id.as_bytes());
    let mut key = [0u8; KEY_LEN];
    hkdf.expand(&info, &mut key)
        .map_err(|_| CoreError::new(ErrorCode::Internal))?;
    Ok(key)
}

fn aad(member_id: &str, vault_id: &VaultId, role: Role) -> Vec<u8> {
    let mut aad = Vec::with_capacity(member_id.len() + 24);
    aad.extend_from_slice(b"CipherKeep/fam1");
    aad.extend_from_slice(vault_id.as_bytes());
    aad.extend_from_slice(member_id.as_bytes());
    aad.push(match role {
        Role::Owner => 1,
        Role::Editor => 2,
        Role::Viewer => 3,
    });
    aad
}

#[cfg(test)]
mod tests {
    use super::{FamilyVault, Member, Role};
    use crate::keys::VaultKey;
    use x25519_dalek::{PublicKey, StaticSecret};

    fn member(id: &str, role: Role) -> (Member, StaticSecret) {
        let secret = StaticSecret::from([id.as_bytes()[0]; 32]);
        (
            Member {
                id: id.to_string(),
                public_key: PublicKey::from(&secret).to_bytes(),
                role,
            },
            secret,
        )
    }

    #[test]
    fn invite_three_members_and_unwrap() {
        let (owner, _) = member("owner", Role::Owner);
        let (alice, alice_secret) = member("alice", Role::Editor);
        let (bob, bob_secret) = member("bob", Role::Viewer);
        let mut family =
            FamilyVault::new(VaultKey::generate().expect("vault"), owner).expect("family");
        family.invite("owner", alice).expect("invite alice");
        family.invite("owner", bob).expect("invite bob");
        assert_eq!(family.members().len(), 3);
        let alice_key = family.unwrap("alice", &alice_secret).expect("unwrap");
        let bob_key = family.unwrap("bob", &bob_secret).expect("unwrap");
        assert_eq!(
            alice_key.secret().expose_secret(),
            bob_key.secret().expose_secret()
        );
        assert!(family.can_write("alice"));
        assert!(!family.can_write("bob"));
    }

    #[test]
    fn revoked_member_cannot_unwrap_new_key() {
        let (owner, _) = member("owner", Role::Owner);
        let (alice, alice_secret) = member("alice", Role::Editor);
        let (bob, bob_secret) = member("bob", Role::Viewer);
        let mut family =
            FamilyVault::new(VaultKey::generate().expect("vault"), owner).expect("family");
        family.invite("owner", alice).expect("alice");
        family.invite("owner", bob).expect("bob");
        let old = family
            .unwrap("bob", &bob_secret)
            .expect("old")
            .secret()
            .expose_secret()
            .to_vec();
        family.revoke("owner", "bob").expect("revoke");
        assert!(family.unwrap("bob", &bob_secret).is_err());
        let new = family.unwrap("alice", &alice_secret).expect("new");
        assert_ne!(old, new.secret().expose_secret());
        assert_eq!(family.epoch(), 2);
    }

    #[test]
    fn non_owner_cannot_invite_or_revoke() {
        let (owner, _) = member("owner", Role::Owner);
        let (alice, _) = member("alice", Role::Editor);
        let (bob, _) = member("bob", Role::Viewer);
        let mut family =
            FamilyVault::new(VaultKey::generate().expect("vault"), owner).expect("family");
        family.invite("owner", alice).expect("alice");
        assert!(family.invite("alice", bob).is_err());
        assert!(family.revoke("alice", "owner").is_err());
    }

    #[test]
    fn audit_has_no_key_material() {
        let (owner, _) = member("owner", Role::Owner);
        let (alice, _) = member("alice", Role::Editor);
        let mut family =
            FamilyVault::new(VaultKey::generate().expect("vault"), owner).expect("family");
        family.invite("owner", alice).expect("invite");
        family.revoke("owner", "alice").expect("revoke");
        let debug = format!("{:?}", family.audit());
        assert!(debug.contains("Invited"));
        assert!(debug.contains("Revoked"));
        assert!(!debug.contains("key"));
    }
}
