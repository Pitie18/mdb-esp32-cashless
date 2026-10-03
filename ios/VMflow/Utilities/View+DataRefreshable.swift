import SwiftUI

extension View {
    /// Pull-to-refresh whose reload work survives SwiftUI cancelling the
    /// gesture's task.
    ///
    /// SwiftUI cancels the `.refreshable` action task **mid-flight**, before the
    /// async work finishes — reproduced deterministically (iOS 18/26): the
    /// action's `Task.isCancelled` flips to `true` while the fetch is still in
    /// its first `await`. Our reload functions catch and silently swallow the
    /// resulting `CancellationError` (they must — SwiftUI legitimately cancels
    /// `.task`-driven reloads when a view goes away), so a pull-to-refresh
    /// fetched nothing and left the screen showing stale data while the spinner
    /// happily retracted as if it had worked.
    ///
    /// Running the reload inside an unstructured `Task` decouples it from the
    /// gesture task's cancellation: the child runs to completion and its
    /// `@Published` writes land, so the screen actually refreshes.
    ///
    /// Use this for every pull-to-refresh that loads data. Do **not** "simplify"
    /// it back to a bare `.refreshable { await load() }` — that reintroduces the
    /// silent no-op.
    func dataRefreshable(_ action: @escaping @Sendable () async -> Void) -> some View {
        refreshable {
            await Task { await action() }.value
        }
    }
}
