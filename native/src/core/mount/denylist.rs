// Descendants must be detached before their parent so a failed parent unmount
// does not skip the children. Preserve repeated paths: each mountinfo entry
// represents a mount, and stacked mounts need one detach per layer.
pub(super) fn sort_unmount_targets(targets: &mut [String]) {
    targets.sort_unstable_by(|left, right| right.cmp(left));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn targets(paths: &[&str]) -> Vec<String> {
        let mut targets = paths
            .iter()
            .map(|path| path.to_string())
            .collect::<Vec<_>>();
        sort_unmount_targets(&mut targets);
        targets
    }

    #[test]
    fn stacked_mounts_are_not_deduplicated() {
        let result = targets(&["/system/bin/su", "/system/bin/su", "/system/bin/su"]);
        assert_eq!(
            result,
            ["/system/bin/su", "/system/bin/su", "/system/bin/su"]
        );
    }

    #[test]
    fn children_are_detached_before_parents() {
        let result = targets(&["/system", "/system/bin", "/system/bin/su"]);
        assert_eq!(result, ["/system/bin/su", "/system/bin", "/system"]);
    }

    #[test]
    fn siblings_with_common_prefix_are_retained() {
        let result = targets(&["/system/lib", "/system/lib64", "/system/lib/a.so"]);
        assert_eq!(result, ["/system/lib64", "/system/lib/a.so", "/system/lib"]);
    }

    #[test]
    fn nested_and_stacked_mounts_are_both_retained() {
        let result = targets(&["/system", "/system/bin/su", "/system", "/system/bin"]);
        assert_eq!(
            result,
            ["/system/bin/su", "/system/bin", "/system", "/system"]
        );
    }

    #[test]
    fn empty_namespace_needs_no_unmounts() {
        assert!(targets(&[]).is_empty());
    }
}
