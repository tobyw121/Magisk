// Enrollment is writable only by root; the certificate pins the generated manager.
pub fn parse(text: &str) -> Option<(String, Vec<u8>)> {
    if text.len() > 32768 {
        return None;
    }
    let mut lines = text.lines();
    let pkg = lines.next()?;
    if pkg.len() > 255
        || !pkg.contains('.')
        || !pkg
            .split('.')
            .all(|s| !s.is_empty() && s.bytes().all(|c| c.is_ascii_lowercase()))
    {
        return None;
    }
    let hex = lines.next()?;
    if hex.is_empty() || hex.len() > 16384 || hex.len() % 2 != 0 || lines.next().is_some() {
        return None;
    }
    let cert: Option<Vec<u8>> = hex
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let hi = (pair[0] as char).to_digit(16)?;
            let lo = (pair[1] as char).to_digit(16)?;
            Some(((hi << 4) | lo) as u8)
        })
        .collect();
    Some((pkg.to_owned(), cert?))
}

#[cfg(test)]
mod tests {
    use super::parse;

    #[test]
    fn pins_exact_bytes() {
        assert_eq!(
            parse("abcdefgh.ijklmnop\n01abff\n"),
            Some(("abcdefgh.ijklmnop".into(), vec![1, 171, 255]))
        );
    }

    #[test]
    fn rejects_invalid_packages() {
        for pkg in ["x", ".x", "x.", "x..y", "x/y.z", "x.y;id", "X.y", "1x.y"] {
            assert!(parse(&format!("{pkg}\n01\n")).is_none());
        }
    }

    #[test]
    fn rejects_invalid_certificates_and_extra_fields() {
        for value in ["x.y\n", "x.y\n0\n", "x.y\nzz\n", "x.y\n01\nextra"] {
            assert!(parse(value).is_none());
        }
        assert!(parse(&format!("x.y\n{}", "00".repeat(10000))).is_none());
    }
}
