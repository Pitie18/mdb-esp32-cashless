import Foundation
import Supabase
import Realtime

/// Central realtime subscription manager.
/// Publishes lightweight change notifications that ViewModels observe to refresh their data.
@MainActor
final class RealtimeService: ObservableObject {
    static let shared = RealtimeService()

    /// Incremented on every relevant DB change — ViewModels use `.task(id:)` to auto-reload.
    @Published var salesVersion: Int = 0
    @Published var traysVersion: Int = 0
    @Published var machinesVersion: Int = 0
    @Published var embeddedVersion: Int = 0
    @Published var warehouseVersion: Int = 0
    @Published var activityVersion: Int = 0

    private var client: SupabaseClient { SupabaseService.shared.client }
    private var channel: RealtimeChannelV2?
    private var listenTask: Task<Void, Never>?

    private init() {}

    /// Subscribe to all relevant postgres changes. Call once at app startup.
    func start() {
        guard channel == nil else { return }

        let ch = client.realtimeV2.channel("app-realtime")
        self.channel = ch

        // IMPORTANT: All postgresChange listeners MUST be registered BEFORE
        // calling subscribe(). The supabase-swift SDK (v2.x) requires callbacks
        // to be added while the channel status is `.unsubscribed` — calling
        // subscribe() changes status to `.subscribing` and then `.subscribed`,
        // after which _onPostgresChange() rejects new callbacks with:
        //   "Cannot add postgres_changes callbacks after subscribe()"
        //
        // The postgresChange() async-stream overloads internally call
        // _onPostgresChange() which registers the callback AND appends to
        // clientChanges (the list sent in the join payload to the server).
        // If listeners are set up after subscribe(), the server never learns
        // about them and no events are delivered.
        let salesStream = ch.postgresChange(InsertAction.self, schema: "public", table: "sales")
        let traysStream = ch.postgresChange(AnyAction.self, schema: "public", table: "machine_trays")
        let machinesStream = ch.postgresChange(AnyAction.self, schema: "public", table: "vendingMachine")
        let embeddedsStream = ch.postgresChange(UpdateAction.self, schema: "public", table: "embeddeds")
        let warehouseStream = ch.postgresChange(AnyAction.self, schema: "public", table: "warehouse_stock_batches")
        let activityStream = ch.postgresChange(InsertAction.self, schema: "public", table: "activity_log")

        listenTask = Task {
            // Subscribe the channel (connects the websocket).
            // The join payload now includes all six postgres_changes filters
            // that were registered above.
            await ch.subscribe()

            // Consume the streams in parallel — each for-await loop runs
            // until the stream terminates (channel unsubscribed / task cancelled).
            async let s: () = consumeSales(salesStream)
            async let t: () = consumeTrays(traysStream)
            async let m: () = consumeMachines(machinesStream)
            async let e: () = consumeEmbeddeds(embeddedsStream)
            async let w: () = consumeWarehouse(warehouseStream)
            async let a: () = consumeActivity(activityStream)
            _ = await (s, t, m, e, w, a)
        }
    }

    /// Disconnect and clean up.
    func stop() {
        listenTask?.cancel()
        listenTask = nil
        if let ch = channel {
            Task { await ch.unsubscribe() }
        }
        channel = nil
    }

    // MARK: - Stream Consumers

    private func consumeSales(_ stream: AsyncStream<InsertAction>) async {
        for await _ in stream {
            salesVersion += 1
            print("[Realtime] New sale detected")
        }
    }

    private func consumeTrays(_ stream: AsyncStream<AnyAction>) async {
        for await _ in stream {
            traysVersion += 1
            print("[Realtime] Tray change detected")
        }
    }

    private func consumeMachines(_ stream: AsyncStream<AnyAction>) async {
        for await _ in stream {
            machinesVersion += 1
            print("[Realtime] Machine change detected")
        }
    }

    private func consumeEmbeddeds(_ stream: AsyncStream<UpdateAction>) async {
        for await _ in stream {
            embeddedVersion += 1
            print("[Realtime] Embedded status change detected")
        }
    }

    private func consumeWarehouse(_ stream: AsyncStream<AnyAction>) async {
        for await _ in stream {
            warehouseVersion += 1
            print("[Realtime] Warehouse stock change detected")
        }
    }

    private func consumeActivity(_ stream: AsyncStream<InsertAction>) async {
        for await insert in stream {
            // Only feed-rendered actions trigger a dashboard reload. Every sale
            // also writes a sale_recorded activity row (mqtt-webhook) — without
            // this filter each sale would reload the dashboard twice (sales
            // stream + activity stream), and unrelated actions (stock_updated,
            // credit_sent, …) would reload it too.
            let action = insert.record["action"]?.stringValue
            guard action == "stock_refill_tour" || action == "tour_started" || action == "slot_change_applied" else { continue }
            activityVersion += 1
            print("[Realtime] New activity_log entry detected")
        }
    }
}
