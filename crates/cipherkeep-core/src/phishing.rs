//! Offline phishing and homograph analysis for autofill origins.
//!
//! No network lookup is performed. The analyzer decodes IDNA, detects mixed
//! scripts and confusable characters, and compares the registrable label with a
//! small bundled brand list.

use idna::domain_to_unicode;
use url::Url;

use crate::error::{CoreError, ErrorCode, Result};

/// Why an origin needs confirmation.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PhishingReason {
    /// A label is punycode or decodes to a different Unicode host.
    Punycode,
    /// More than one non-common script appears.
    MixedScript,
    /// A Cyrillic/Greek lookalike spells a known brand.
    ConfusableBrand,
    /// A label is one edit away from a known brand.
    LookalikeBrand,
}

/// Result of offline origin analysis.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OriginAssessment {
    /// Host as supplied by the URL parser.
    pub ascii_host: String,
    /// Unicode form after IDNA decoding.
    pub decoded_host: String,
    /// Scripts found in the decoded host.
    pub scripts: Vec<&'static str>,
    /// Reasons requiring explicit confirmation.
    pub reasons: Vec<PhishingReason>,
}

impl OriginAssessment {
    /// Whether autofill must ask before filling.
    #[must_use]
    pub fn requires_confirmation(&self) -> bool {
        !self.reasons.is_empty()
    }
}

const BRANDS: &[&str] = &[
    "amazon",
    "apple",
    "binance",
    "coinbase",
    "dropbox",
    "facebook",
    "github",
    "google",
    "instagram",
    "linkedin",
    "microsoft",
    "netflix",
    "paypal",
    "whatsapp",
];

/// Analyze a URL's origin without network access.
///
/// # Errors
///
/// Rejects URLs without a host.
pub fn analyze_url(raw: &str) -> Result<OriginAssessment> {
    let url = Url::parse(raw).map_err(|_| CoreError::new(ErrorCode::InvalidArgument))?;
    let host = url
        .host_str()
        .ok_or_else(|| CoreError::new(ErrorCode::InvalidArgument))?
        .to_ascii_lowercase();
    analyze_host(&host)
}

/// Analyze an already parsed host.
///
/// # Errors
///
/// Rejects empty or malformed hosts.
pub fn analyze_host(host: &str) -> Result<OriginAssessment> {
    let host = host.trim().trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() || host.len() > 253 || host.contains('/') {
        return Err(CoreError::new(ErrorCode::InvalidArgument));
    }
    let (decoded, idna_error) = domain_to_unicode(&host);
    let decoded = decoded.to_ascii_lowercase();
    let mut reasons = Vec::new();
    if idna_error.is_err() || host.split('.').any(|label| label.starts_with("xn--")) {
        reasons.push(PhishingReason::Punycode);
    }
    let scripts = scripts_of(&decoded);
    if scripts.iter().filter(|script| **script != "Common").count() > 1 {
        reasons.push(PhishingReason::MixedScript);
    }
    for label in decoded.split('.') {
        let skeleton = confusable_skeleton(label);
        let brand_match = BRANDS.iter().find(|brand| **brand == skeleton);
        if let Some(brand) = brand_match {
            if *brand != label {
                reasons.push(PhishingReason::ConfusableBrand);
            }
        } else if BRANDS
            .iter()
            .any(|brand| edit_distance(brand, &skeleton) == 1)
            && label.len() >= 5
        {
            reasons.push(PhishingReason::LookalikeBrand);
        }
    }
    reasons.sort_unstable_by_key(|reason| *reason as u8);
    reasons.dedup();
    Ok(OriginAssessment {
        ascii_host: host,
        decoded_host: decoded,
        scripts,
        reasons,
    })
}

fn scripts_of(value: &str) -> Vec<&'static str> {
    let mut scripts = Vec::new();
    for character in value.chars() {
        let script = script_of(character);
        if !scripts.contains(&script) {
            scripts.push(script);
        }
    }
    scripts
}

fn script_of(character: char) -> &'static str {
    let code = character as u32;
    match code {
        0x0041..=0x024F | 0x1E00..=0x1EFF => "Latin",
        0x0370..=0x03FF | 0x1F00..=0x1FFF => "Greek",
        0x0400..=0x052F => "Cyrillic",
        0x0590..=0x05FF => "Hebrew",
        0x0600..=0x06FF => "Arabic",
        0x3040..=0x309F => "Hiragana",
        0x30A0..=0x30FF => "Katakana",
        0x4E00..=0x9FFF => "Han",
        0xAC00..=0xD7AF => "Hangul",
        0x0900..=0x097F => "Devanagari",
        _ => "Common",
    }
}

fn confusable_skeleton(value: &str) -> String {
    value
        .chars()
        .map(|character| match character {
            'а' | 'α' => 'a',
            'е' | 'ε' => 'e',
            'о' | 'ο' => 'o',
            'р' | 'ρ' => 'p',
            'с' => 'c',
            'х' | 'χ' => 'x',
            'і' | 'ι' => 'i',
            'ј' => 'j',
            'ѕ' => 's',
            'ԁ' => 'd',
            'у' | 'γ' => 'y',
            'к' => 'k',
            'м' => 'm',
            'т' => 't',
            'н' => 'h',
            'в' => 'b',
            'г' => 'r',
            other => other,
        })
        .collect()
}

fn edit_distance(left: &str, right: &str) -> usize {
    let left: Vec<char> = left.chars().collect();
    let right: Vec<char> = right.chars().collect();
    let mut previous: Vec<usize> = (0..=right.len()).collect();
    for (row, left_char) in left.iter().enumerate() {
        let mut current = vec![row + 1];
        for (column, right_char) in right.iter().enumerate() {
            let cost = usize::from(left_char != right_char);
            current.push(
                (previous[column + 1] + 1)
                    .min(current[column] + 1)
                    .min(previous[column] + cost),
            );
        }
        previous = current;
    }
    previous[right.len()]
}

#[cfg(test)]
mod tests {
    use super::{analyze_host, PhishingReason};

    #[test]
    fn plain_brand_is_safe() {
        let result = analyze_host("github.com").expect("host");
        assert!(!result.requires_confirmation());
        assert!(result.reasons.is_empty());
    }

    #[test]
    fn cyrillic_lookalike_warns() {
        let result = analyze_host("аpple.com").expect("host");
        assert!(result.reasons.contains(&PhishingReason::ConfusableBrand));
        assert!(result.requires_confirmation());
    }

    #[test]
    fn mixed_script_domain_warns() {
        let result = analyze_host("gооgle.com").expect("host");
        assert!(result.reasons.contains(&PhishingReason::MixedScript));
        assert!(result.requires_confirmation());
    }

    #[test]
    fn punycode_decodes_and_warns() {
        let result = analyze_host("xn--80ak6aa92e.com").expect("host");
        assert!(result.reasons.contains(&PhishingReason::Punycode));
        assert_ne!(result.ascii_host, result.decoded_host);
    }

    #[test]
    fn legitimate_idn_is_not_lookalike() {
        let result = analyze_host("münchen.de").expect("host");
        assert!(!result.reasons.contains(&PhishingReason::ConfusableBrand));
        assert!(!result.reasons.contains(&PhishingReason::LookalikeBrand));
        assert_eq!(result.decoded_host, "münchen.de");
    }

    #[test]
    fn typosquat_warns() {
        let result = analyze_host("gooogle.com").expect("host");
        assert!(result.reasons.contains(&PhishingReason::LookalikeBrand));
    }
}
