//! Signed, origin-scoped autofill recipe registry.
//!
//! Recipes are data only: verified selectors and origins, never JavaScript or
//! HTML. The registry is rejected unless its detached Ed25519 signature matches
//! the pinned public key and its version moves forward.

use data_encoding::HEXLOWER;
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde_json::Value;
use url::Url;

use crate::error::{CoreError, ErrorCode, Result};

/// Maximum recipes in one registry.
pub const MAX_RECIPES: usize = 1_000;
/// Maximum selector length.
pub const MAX_SELECTOR_LEN: usize = 512;
/// Maximum origin length.
pub const MAX_ORIGIN_LEN: usize = 255;

/// One origin-scoped autofill recipe.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Recipe {
    origin: String,
    username_selector: String,
    password_selector: String,
    submit_selector: Option<String>,
}

impl Recipe {
    /// Exact web origin (host only).
    #[must_use]
    pub fn origin(&self) -> &str {
        &self.origin
    }

    /// Username selector.
    #[must_use]
    pub fn username_selector(&self) -> &str {
        &self.username_selector
    }

    /// Password selector.
    #[must_use]
    pub fn password_selector(&self) -> &str {
        &self.password_selector
    }

    /// Explicit submit selector, or none. The autofill engine never submits.
    #[must_use]
    pub fn submit_selector(&self) -> Option<&str> {
        self.submit_selector.as_deref()
    }
}

/// Verified recipe registry.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RecipeRegistry {
    version: u64,
    recipes: Vec<Recipe>,
}

impl RecipeRegistry {
    /// Registry version used for rollback rejection.
    #[must_use]
    pub const fn version(&self) -> u64 {
        self.version
    }

    /// Recipes in the registry.
    #[must_use]
    pub fn recipes(&self) -> &[Recipe] {
        &self.recipes
    }

    /// Find a recipe for an exact origin.
    #[must_use]
    pub fn for_origin(&self, origin: &str) -> Option<&Recipe> {
        self.recipes
            .iter()
            .find(|recipe| recipe.origin.eq_ignore_ascii_case(origin))
    }

    /// Refuse a registry that does not advance the current version.
    ///
    /// # Errors
    ///
    /// Returns [`ErrorCode::Conflict`] for an equal or older version.
    pub fn accept_if_newer(&self, current_version: u64) -> Result<()> {
        if self.version <= current_version {
            return Err(CoreError::new(ErrorCode::Conflict));
        }
        Ok(())
    }
}

/// Verify and parse a signed recipe registry.
///
/// # Errors
///
/// Returns a redacted error for a malformed key/signature, bad signature,
/// invalid origin/selector or version zero.
pub fn verify_signed_registry(
    json: &[u8],
    signature_hex: &str,
    public_key_hex: &str,
) -> Result<RecipeRegistry> {
    let public_key = HEXLOWER
        .decode(public_key_hex.as_bytes())
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let public_key: [u8; 32] = public_key
        .try_into()
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let verifying_key = VerifyingKey::from_bytes(&public_key)
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let signature = HEXLOWER
        .decode(signature_hex.as_bytes())
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let signature = Signature::from_slice(&signature)
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    verifying_key
        .verify(json, &signature)
        .map_err(|_| CoreError::new(ErrorCode::AuthenticationFailed))?;
    parse_registry(json)
}

fn parse_registry(json: &[u8]) -> Result<RecipeRegistry> {
    let value: Value =
        serde_json::from_slice(json).map_err(|_| CoreError::new(ErrorCode::CorruptData))?;
    let version = value
        .get("version")
        .and_then(Value::as_u64)
        .filter(|version| *version > 0)
        .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
    let entries = value
        .get("recipes")
        .and_then(Value::as_array)
        .ok_or_else(|| CoreError::new(ErrorCode::CorruptData))?;
    if entries.len() > MAX_RECIPES {
        return Err(CoreError::new(ErrorCode::LimitExceeded));
    }
    let mut recipes = Vec::with_capacity(entries.len());
    for entry in entries {
        let origin = required_string(entry, "origin")?;
        validate_origin(&origin)?;
        let username_selector = required_string(entry, "usernameSelector")?;
        let password_selector = required_string(entry, "passwordSelector")?;
        validate_selector(&username_selector)?;
        validate_selector(&password_selector)?;
        let submit_selector = entry
            .get("submitSelector")
            .map(|value| {
                value
                    .as_str()
                    .map(ToString::to_string)
                    .ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))
            })
            .transpose()?;
        if let Some(selector) = &submit_selector {
            validate_selector(selector)?;
        }
        recipes.push(Recipe {
            origin: origin.to_ascii_lowercase(),
            username_selector,
            password_selector,
            submit_selector,
        });
    }
    Ok(RecipeRegistry { version, recipes })
}

fn required_string(value: &Value, key: &str) -> Result<String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .map(ToString::to_string)
        .ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))
}

fn validate_origin(origin: &str) -> Result<()> {
    if origin.is_empty()
        || origin.len() > MAX_ORIGIN_LEN
        || origin.chars().any(|character| {
            character.is_control()
                || character.is_whitespace()
                || matches!(character, '/' | ':' | '@' | '?' | '#')
        })
    {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let url = Url::parse(&format!("https://{origin}/"))
        .map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    if url.host_str() != Some(origin) {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    Ok(())
}

fn validate_selector(selector: &str) -> Result<()> {
    if selector.is_empty()
        || selector.len() > MAX_SELECTOR_LEN
        || selector.chars().any(char::is_control)
        || selector.to_ascii_lowercase().contains("javascript:")
        || selector.contains("eval(")
        || selector.contains("innerHTML")
    {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    fn signed(json: &str) -> (String, String) {
        let key = SigningKey::from_bytes(&[7u8; 32]);
        let signature = key.sign(json.as_bytes());
        (HEXLOWER.encode(signature.to_bytes().as_slice()), HEXLOWER.encode(key.verifying_key().to_bytes().as_slice()))
    }

    const REGISTRY: &str = r##"{"version":2,"recipes":[{"origin":"example.com","usernameSelector":"#user","passwordSelector":"#pass","submitSelector":"button[type=submit]"}]}"##;

    #[test]
    fn valid_signature_and_selector_are_accepted() {
        let (signature, key) = signed(REGISTRY);
        let registry = verify_signed_registry(REGISTRY.as_bytes(), &signature, &key).expect("registry");
        assert_eq!(registry.version(), 2);
        assert!(registry.for_origin("EXAMPLE.com").is_some());
    }

    #[test]
    fn tampered_payload_or_wrong_key_is_rejected() {
        let (signature, key) = signed(REGISTRY);
        let tampered = REGISTRY.replace("#pass", "#evil");
        assert!(verify_signed_registry(tampered.as_bytes(), &signature, &key).is_err());
        let other_key = HEXLOWER.encode(SigningKey::from_bytes(&[8u8; 32]).verifying_key().to_bytes().as_slice());
        assert!(verify_signed_registry(REGISTRY.as_bytes(), &signature, &other_key).is_err());
    }

    #[test]
    fn rollback_and_bad_selectors_are_refused() {
        let (signature, key) = signed(REGISTRY);
        let registry = verify_signed_registry(REGISTRY.as_bytes(), &signature, &key).expect("registry");
        assert_eq!(registry.accept_if_newer(2).unwrap_err().code(), ErrorCode::Conflict);
        assert!(registry.accept_if_newer(1).is_ok());
    }

    #[test]
    fn script_and_cross_origin_shapes_are_rejected() {
        for origin in ["https://example.com", "example.com/path", "example.com:443"] {
            assert!(validate_origin(origin).is_err());
        }
        assert!(validate_selector("javascript:alert(1)").is_err());
        assert!(validate_selector("innerHTML").is_err());
    }
}
