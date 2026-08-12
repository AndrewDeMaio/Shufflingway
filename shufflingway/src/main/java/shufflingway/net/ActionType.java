package shufflingway.net;

public enum ActionType {

    // ── Handshake ─────────────────────────────────────────────────────────────
    HELLO,          // payload: { "version": "1.0.0", "cardChecksum": "abc123..." }
    READY,          // payload: {} — both sides ready, game can begin

    // ── Lobby / match setup ───────────────────────────────────────────────────
    DECK_LIST,      // payload: { "deckName": "...", "serials": ["1-001H", ...] }
                    //   The sender's own deck, expanded one entry per copy and ordered by
                    //   serial — the same order getDeckCardsDetailed produces locally.
    GAME_SETUP,     // payload: { "seed": <long>, "hostGoesFirst": <bool> }
                    //   Host-authored. The seed drives both decks' shuffles on both clients.
    STATE_CHECKSUM, // payload: { "label": "...", "checksum": "..." }
                    //   Desync detection: a hash of state both clients must agree on.

    // ── Opening hand ──────────────────────────────────────────────────────────
    KEEP_HAND,      // payload: { "order": [cardIdx, ...] }
    MULLIGAN,       // payload: { "bottomOrder": [cardIdx, ...] }

    // ── Turn flow ─────────────────────────────────────────────────────────────
    ADVANCE_PHASE,  // payload: {}

    // ── Card actions ──────────────────────────────────────────────────────────
    PLAY_CARD,      // payload: { "handIdx": n, "card": "...", "discards": [idx, ...],
                    //            "backups": [slot, ...], "backupElements": { "slot": "Fire" } }
                    //   Indices address zones both clients hold in the same order.
    DISCARD_HAND,   // payload: { "indices": [idx, ...] } — a discard with no CP, e.g. the
                    //   end-phase trim to five. Replicated because it renumbers the hand.
    ATTACK,         // payload: { "zone": "FORWARD"|"MONSTER"|"BACKUP", "indices": [n, ...],
                    //            "power": n }
                    //   The sender's own attackers, so the side flips on arrival; the slot indices
                    //   do not, both clients holding each zone in the same order. More than one
                    //   index is a party attack, which is always FORWARD. "power" is the sender's
                    //   effective total, carried only so the receiver can cross-check it.
    BLOCK,          // payload: { "blocked": bool, "zone": "...", "idx": n,
                    //            "damage": { "<attackerIdx>": amount } }
                    //   The answer to an ATTACK. "damage" is the blocker's spread across a blocked
                    //   party and is absent otherwise. When "blocked" is false the rest is absent.
    CHOICE,         // payload: { "kind": "<ChoiceKind>", "indices": [n, ...] }
                    //   One player's answer to a decision the other is parked on while an effect
                    //   resolves. See ChoiceKind for what each kind's integers index, and which
                    //   of them flip sides on arrival: hand and slot indices do not, both clients
                    //   holding each zone in the same order, while a field code names a side.
    RESOLVE_STACK,  // payload: {}

    // ── Utility ───────────────────────────────────────────────────────────────
    PING,           // payload: {} — keep-alive
    CHAT,           // payload: { "message": "..." }
    DISCONNECT      // payload: { "reason": "..." }
}
