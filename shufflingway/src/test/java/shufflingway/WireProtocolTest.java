package shufflingway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import shufflingway.net.ChoiceKind;
import shufflingway.net.ConnectionListener;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;

/**
 * The choice protocol over a real TCP socket.
 *
 * <p>Everything else asserting on multiplayer builds both sides in one JVM and compares them, which
 * never touches the transport: {@link MultiplayerSetupTest} proves an answer <em>encodes</em>
 * correctly, and this proves it arrives. The two halves failed differently — an answer that encodes
 * fine and never lands leaves the other client parked on a modal wait forever, which is the failure
 * mode of the whole seam.
 *
 * <p>Loopback, on an ephemeral port, with the same {@link GameConnection} the game uses. What is
 * still out of reach is the layer above: {@code awaitChoice} parks in a Swing modal and needs
 * {@code MainWindow}, so the dialogs remain a two-window manual check.
 */
class WireProtocolTest {

    private ServerSocket   listener;
    private Socket         hostSide, joinerSide;
    private GameConnection host,     joiner;

    /** Actions the joiner's reader thread has delivered, oldest first. */
    private final BlockingQueue<GameAction> inbox = new ArrayBlockingQueue<>(64);

    @BeforeEach
    void connect() throws IOException {
        listener   = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        joinerSide = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        hostSide   = listener.accept();

        host   = new GameConnection(hostSide);
        joiner = new GameConnection(joinerSide);
        joiner.addListener(new ConnectionListener() {
            @Override public void onActionReceived(GameAction action) { inbox.add(action); }
            @Override public void onDisconnected(String reason) { }
        });
        joiner.start();
    }

    @AfterEach
    void disconnect() throws IOException {
        if (host     != null) host.close();
        if (joiner   != null) joiner.close();
        if (listener != null) listener.close();
    }

    /** The next action to arrive, or a failure — never a hang, which is the bug being hunted. */
    private GameAction next() throws InterruptedException {
        GameAction action = inbox.poll(5, TimeUnit.SECONDS);
        assertNotNull(action, "nothing arrived within 5s — the far client would still be waiting");
        return action;
    }

    private static List<Integer> indicesOf(GameAction action) {
        JSONArray raw = action.payload().getJSONArray("indices");
        List<Integer> out = new ArrayList<>(raw.length());
        for (int i = 0; i < raw.length(); i++) out.add(raw.getInt(i));
        return out;
    }

    /** Sends one answer of {@code kind} and reads back what landed on the other side. */
    private List<Integer> roundTrip(ChoiceKind kind, List<Integer> answer)
            throws InterruptedException {
        host.send(RemoteOpponent.choiceAction(kind, answer));
        GameAction arrived = next();
        assertEquals(kind.name(), arrived.payload().getString("kind"));
        return indicesOf(arrived);
    }

    @Test
    void everyKindOfAnswerSurvivesTheSocket() throws InterruptedException {
        // The payload shape differs per kind, so each gets one that looks like the real thing.
        for (ChoiceKind kind : ChoiceKind.values()) {
            List<Integer> answer = switch (kind) {
                case PRIORITY_PASS  -> List.of();
                case NAMED          -> List.of(NamedThing.Vocabulary.ELEMENT.ordinal(), 5);
                case DECK_LOOK      -> new DeckLookDecision(List.of(0), List.of(), List.of(3),
                                            List.of(1), List.of(2)).toAnswer();
                case OWN_FIELD_CARD -> List.of(new ForwardTarget(true, 2,
                                            ForwardTarget.CardZone.FORWARD).choiceCode());
                default             -> List.of(1, 0, 2);
            };
            assertEquals(answer, roundTrip(kind, answer),
                    kind + " did not arrive as it was sent");
        }
    }

    @Test
    void aPassCarryingNothingStillArrives() throws InterruptedException {
        assertEquals(List.of(), roundTrip(ChoiceKind.PRIORITY_PASS, List.of()),
                "the message is the whole answer; an empty payload that vanished would leave the "
                + "combat window it releases open forever");
    }

    @Test
    void aDeckLookArrivesAsTheSameArrangement() throws InterruptedException {
        DeckLookDecision sent = new DeckLookDecision(List.of(0), List.of(4), List.of(3),
                                                     List.of(1), List.of(2));
        List<Integer> landed = roundTrip(ChoiceKind.DECK_LOOK, sent.toAnswer());
        assertEquals(sent, DeckLookDecision.fromAnswer(landed, 5),
                "five cards went to five different destinations and all five have to survive");
    }

    @Test
    void aNamedThingArrivesNamingTheSameThing() throws InterruptedException {
        List<NamedThing> sent = NamedThing.of(NamedThing.Vocabulary.ELEMENT, "Water");
        List<Integer> landed = roundTrip(ChoiceKind.NAMED, NamedThing.toAnswer(sent, msg -> {}));
        assertEquals(sent, NamedThing.fromAnswer(landed, msg -> {}));
    }

    @Test
    void aFieldTargetArrivesOnTheOtherSideOfTheBoard() throws InterruptedException {
        ForwardTarget mine = new ForwardTarget(true, 1, ForwardTarget.CardZone.FORWARD);
        List<Integer> landed = roundTrip(ChoiceKind.OWN_FIELD_CARD, List.of(mine.choiceCode()));

        ForwardTarget theirs = ForwardTarget.fromChoiceCode(
                ForwardTarget.flipChoiceSide(landed.get(0)));
        assertEquals(new ForwardTarget(false, 1, ForwardTarget.CardZone.FORWARD), theirs,
                "the sender packed their own side, and it is the receiver's opponent's");
    }

    @Test
    void answersArriveInTheOrderTheyWereSent() throws InterruptedException {
        // The protocol is one question at a time, but a pass can follow an answer immediately, and
        // newline framing is the only thing keeping two actions in one write buffer apart.
        host.send(RemoteOpponent.choiceAction(ChoiceKind.DECK_LOOK, List.of(1, 0, 0, 0, 0)));
        host.send(RemoteOpponent.choiceAction(ChoiceKind.PRIORITY_PASS, List.of()));
        host.send(RemoteOpponent.choiceAction(ChoiceKind.MAY, List.of(1)));

        assertEquals("DECK_LOOK",     next().payload().getString("kind"));
        assertEquals("PRIORITY_PASS", next().payload().getString("kind"));
        assertEquals("MAY",           next().payload().getString("kind"));
    }

    @Test
    void aCardNameWithAwkwardCharactersDoesNotBreakTheFraming() throws InterruptedException {
        // Actions carry card names for the receiver to check indices against, and the transport is
        // newline-delimited: a name holding a newline or a quote must not split one action in two.
        String awkward = "Cid \"the \nEngineer\" (VII)\r\n— Multi-Element";
        host.send(GameAction.of(shufflingway.net.ActionType.PLAY_CARD,
                new org.json.JSONObject().put("card", awkward).put("handIdx", 3)));

        GameAction arrived = next();
        assertEquals(awkward, arrived.payload().getString("card"),
                "JSON escaping is what keeps a newline inside a value from ending the line");
        assertEquals(3, arrived.payload().getInt("handIdx"));
        assertTrue(inbox.isEmpty(), "one action was sent, so exactly one should have arrived");
    }
}
