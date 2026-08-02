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
    PLAY_CARD,      // payload: { "handIdx": n, "discards": [idx, ...], "backups": [slot, ...] }
    ATTACK,         // payload: { "forwardIdx": n }
    RESOLVE_STACK,  // payload: {}

    // ── Utility ───────────────────────────────────────────────────────────────
    PING,           // payload: {} — keep-alive
    CHAT,           // payload: { "message": "..." }
    DISCONNECT      // payload: { "reason": "..." }
}
