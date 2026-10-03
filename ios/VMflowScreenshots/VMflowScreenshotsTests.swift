import XCTest

/// Drives the app under `-UITestFixtures` through five screens and captures
/// an App Store screenshot at each stop via fastlane `snapshot()`.
///
/// Navigation is entirely identifier-based (never localized text) so the
/// same test runs unmodified under both `en-US` and `de-DE` in the Snapfile's
/// `languages` list — see `docs/superpowers/plans/2026-07-17-ios-screenshot-automation.md`
/// review correction 7.
@MainActor
final class VMflowScreenshotsTests: XCTestCase {
    var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        setupSnapshot(app)
        app.launchArguments += ["-UITestFixtures"]
        app.launch()
    }

    /// Locates an accessibility element by identifier regardless of the
    /// `XCUIElement.ElementType` SwiftUI happened to surface it as.
    ///
    /// Every anchor used below is a plain SwiftUI container (`VStack`/
    /// `HStack`) with a bare `.accessibilityIdentifier(...)` — SwiftUI turns
    /// that into a generic accessibility element (`.other`), not a `.button`
    /// or `.cell`, even when the view has a tap gesture. Querying `.any`
    /// avoids having to know the exact type each screen happens to produce.
    private func anchor(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    /// Opens a top-level section in whichever layout the current device
    /// surfaces: the bottom tab bar (iPhone / compact size class) or the
    /// `NavigationSplitView` sidebar (iPad / regular). The tab ids come from
    /// `CompactTabView`, the sidebar ids from `SidebarNavigationView` — both
    /// are locale-independent so the same test runs under en-US and de-DE.
    private func openSection(tab tabID: String, sidebar sidebarID: String) {
        let tabButton = app.tabBars.buttons[tabID]
        if tabButton.waitForExistence(timeout: 3) {
            tabButton.tap()
        } else {
            anchor(sidebarID).tap()
        }
    }

    func testCaptureAppStoreScreenshots() throws {
        // 1. Dashboard — wait for the offline-machines banner, which only
        // renders once machine data has loaded AND at least one machine is
        // offline (fixtures deliberately have 2 of 3 machines online).
        let dashboardAnchor = anchor("dashboard-offline-banner")
        XCTAssertTrue(dashboardAnchor.waitForExistence(timeout: 15), "Dashboard fixture data did not load")
        snapshot("01Dashboard")

        // iPhone shows a bottom tab bar; iPad shows the sidebar split view.
        // Drives which navigation path (and whether a push happens) below.
        let isCompact = app.tabBars.firstMatch.exists

        // 2. Machines — tab bar (iPhone) or sidebar (iPad), then wait for the
        // first machine cell. On iPad `machine-cell` is the split view's card
        // button; on iPhone it's the list row — same identifier either way.
        openSection(tab: "tab-machines", sidebar: "sidebar-machines")
        let firstMachineCell = anchor("machine-cell")
        XCTAssertTrue(firstMachineCell.waitForExistence(timeout: 15), "Machine list did not load")
        snapshot("02Machines")

        // 3. Machine detail — tap the first cell; the Overview tab (index 0,
        // the default) shows the tray list once trays have loaded. Fixtures
        // provide 18 trays across 3 machines, so this always has content. On
        // iPhone this pushes a detail; on iPad it fills the split's right pane.
        firstMachineCell.tap()
        let firstTrayRow = anchor("tray-row")
        XCTAssertTrue(firstTrayRow.waitForExistence(timeout: 15), "Machine detail trays did not load")
        snapshot("03MachineDetail")

        // Back to the machine list — only needed on iPhone, where the detail
        // was pushed onto a NavigationStack. On iPad the detail lives in the
        // split's right column (no push) and the next sidebar tap replaces it,
        // so there is no back button to press. Index-based (not label text,
        // which is localized and/or shows the previous title).
        if isCompact {
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }

        // 4. Refill — the wizard's first step is Review (not Packing):
        // `RefillWizardViewModel.loadData()` skips straight to `.packing`
        // only when there are zero replacement suggestions, and the fixture
        // machine_trays include one unassigned slot (item 16, product_id
        // null), which always produces at least one suggestion. No saved
        // tour state exists under fixtures, so the resume alert cannot fire
        // and `.task` goes straight into `loadData()`.
        openSection(tab: "tab-refill", sidebar: "sidebar-refill")
        let firstReplacementCard = anchor("refill-replacement-card")
        XCTAssertTrue(firstReplacementCard.waitForExistence(timeout: 15), "Refill review data did not load")
        snapshot("04Refill")

        // 5. Warehouse — wait for the first stock summary row.
        openSection(tab: "tab-warehouse", sidebar: "sidebar-warehouse")
        let firstStockRow = anchor("warehouse-stock-row")
        XCTAssertTrue(firstStockRow.waitForExistence(timeout: 15), "Warehouse stock did not load")
        snapshot("05Warehouse")
    }
}
