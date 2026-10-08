import Foundation
import Supabase

/// Reads and queues slot change requests ("change notes"). Shared by the
/// machine analysis (queue a replacement) and the refill tour (read the open
/// requests, queue review-step replacements). Mirrors the PWA's
/// `useSlotChangeRequests` (`fetchOpenRequests` / `saveRequest`).
@MainActor
enum SlotChangeService {
    private static var client: SupabaseClient { SupabaseService.shared.client }

    private struct ChangeRequestRow: Decodable {
        let id: UUID
        let machineId: UUID
        let note: String?
        enum CodingKeys: String, CodingKey {
            case id, note
            case machineId = "machine_id"
        }
    }

    /// Open change requests with their pending slots, keyed by machine id.
    /// A request without pending slots is left out.
    static func fetchOpenRequests(machineIds: [UUID]) async throws -> [UUID: SlotChangeRequest] {
        guard !machineIds.isEmpty else { return [:] }
        let requests: [ChangeRequestRow] = try await client
            .from("slot_change_requests")
            .select("id, machine_id, note")
            .eq("status", value: "open")
            .in("machine_id", values: machineIds.map(\.uuidString))
            .execute()
            .value
        guard !requests.isEmpty else { return [:] }

        let items: [SlotChangeRequestItem] = try await client
            .from("slot_change_request_items")
            .select(SlotChangeRequestItem.selectColumns)
            .eq("status", value: "pending")
            .in("request_id", values: requests.map(\.id.uuidString))
            .order("item_number", ascending: true)
            .execute()
            .value

        let itemsByRequest = Dictionary(grouping: items, by: \.requestId)
        var result: [UUID: SlotChangeRequest] = [:]
        for request in requests {
            guard let own = itemsByRequest[request.id], !own.isEmpty else { continue }
            result[request.machineId] = SlotChangeRequest(
                id: request.id, machineId: request.machineId, note: request.note, items: own
            )
        }
        return result
    }

    /// The open request of one machine, nil when there is none.
    static func fetchOpenRequest(machineId: UUID) async throws -> SlotChangeRequest? {
        try await fetchOpenRequests(machineIds: [machineId])[machineId]
    }

    /// Queue new products for slots of one machine into its open change
    /// request (`save_slot_change_request`) instead of switching the slots
    /// now: the slot is rebuilt at the machine on the next refill tour, where
    /// the old stock is counted. The RPC replaces the pending slots, so the
    /// open ones are merged in; a slot keeps its planned capacity, else its
    /// current one. The note is left as it is.
    ///
    /// Returns the tray ids that have a pending change afterwards.
    /// Mirrors the PWA's `useMachineAnalysis.applySwap`.
    @discardableResult
    static func queueProducts(
        machineId: UUID,
        changes newChanges: [(trayId: UUID, productId: UUID)]
    ) async throws -> Set<UUID> {
        let open = try await fetchOpenRequest(machineId: machineId)

        struct CapacityRow: Decodable { let id: UUID; let capacity: Int }
        var capacityByTray: [UUID: Int] = [:]
        if !newChanges.isEmpty {
            let capacityRows: [CapacityRow] = try await client
                .from("machine_trays")
                .select("id, capacity")
                .in("id", values: newChanges.map(\.trayId.uuidString))
                .execute()
                .value
            capacityByTray = Dictionary(
                capacityRows.map { ($0.id, $0.capacity) },
                uniquingKeysWith: { first, _ in first }
            )
        }

        var order: [UUID] = []
        var changes: [UUID: (productId: UUID?, capacity: Int)] = [:]
        for item in open?.items ?? [] {
            if changes[item.trayId] == nil { order.append(item.trayId) }
            changes[item.trayId] = (item.toProductId, item.toCapacity)
        }
        for change in newChanges {
            let capacity = changes[change.trayId]?.capacity ?? capacityByTray[change.trayId] ?? 1
            if changes[change.trayId] == nil { order.append(change.trayId) }
            changes[change.trayId] = (change.productId, max(1, capacity))
        }

        let payload: [AnyJSON] = order.compactMap { trayId in
            guard let change = changes[trayId] else { return nil }
            return AnyJSON.object([
                "tray_id": .string(trayId.uuidString),
                "to_product_id": change.productId.map { AnyJSON.string($0.uuidString) } ?? .null,
                "to_capacity": .integer(change.capacity),
            ])
        }
        let params: [String: AnyJSON] = [
            "p_machine_id": .string(machineId.uuidString),
            "p_items": .array(payload),
            "p_note": .null,
        ]
        try await client.rpc("save_slot_change_request", params: params).execute()
        return Set(order)
    }
}
