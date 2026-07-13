package com.naztech.oms.exchange.itch;

import com.naztech.oms.entity.Security;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ITCH messages an exchange sends at the start and end of a trading day.
 *
 * <p>A real feed does not simply begin emitting orders. It announces the session, republishes its
 * reference data — the tick tables, the companies, the instrument directory — declares each
 * orderbook open for trading, and only then streams the book. A client that connects at 09:55 must
 * be able to build its entire world from the feed alone, which is exactly why the directory is
 * rebroadcast every morning rather than assumed.
 *
 * <p>These are the real ITCH v2.2 message types from {@link Itch}, encoded and decoded through the
 * real {@link ItchCodec} like every other message, so the day-start path is exercised on the wire
 * rather than short-circuited in memory.
 *
 * <h2>System event codes (ITCH v2.2)</h2>
 * <pre>
 *   O  Start of Messages        C  End of Messages
 *   S  Start of System Hours    E  End of System Hours
 *   Q  Start of Market Hours    M  End of Market Hours
 * </pre>
 */
public final class ItchDayBroadcast {

    /** ITCH sends the whole market under one group; DSE's equity group. */
    static final String GROUP = "DSE";

    /** Tick tables referenced by the orderbook directory. One is enough for the seeded instruments. */
    private static final long PRICE_TICK_TABLE = 1;
    private static final long QTY_TICK_TABLE = 1;

    private ItchDayBroadcast() {
    }

    /**
     * The opening sequence, in the order a real feed emits it:
     * session start → tick tables → companies → instrument directory → "this book is now trading".
     */
    public static List<Itch.Msg> open(List<Security> instruments, long ts, int priceDecimals) {
        List<Itch.Msg> out = new ArrayList<>();

        out.add(new Itch.Timestamp(ts));
        out.add(new Itch.SystemEvent(ts, GROUP, 'O', 0));      // start of messages
        out.add(new Itch.SystemEvent(ts, GROUP, 'S', 0));      // start of system hours

        // Reference data — the tick ladders every price and quantity on the feed is quoted against.
        out.add(new Itch.PriceTick(ts, PRICE_TICK_TABLE, 10, 0));   // DSE equity tick: 0.10
        out.add(new Itch.QtyTick(ts, QTY_TICK_TABLE, 1, 1));

        for (Security s : instruments) {
            out.add(new Itch.CompanyDirectory(ts, s.getId(), trim(s.getName(), 30), trim(s.getSymbol(), 30)));
            out.add(directory(s, ts, priceDecimals));
        }

        out.add(new Itch.SystemEvent(ts, GROUP, 'Q', 0));      // start of market hours — trading begins

        // Each orderbook is declared open. 'T' = trading, in ITCH's trading-state alphabet.
        for (Security s : instruments) {
            out.add(new Itch.TradingAction(ts, s.getId(), 'T', ' '));
        }
        return out;
    }

    /** The closing sequence: halt each book, then wind the session down. */
    public static List<Itch.Msg> close(List<Security> instruments, long ts) {
        List<Itch.Msg> out = new ArrayList<>();
        for (Security s : instruments) {
            out.add(new Itch.TradingAction(ts, s.getId(), 'H', ' '));   // halted — the day is done
        }
        out.add(new Itch.SystemEvent(ts, GROUP, 'M', 0));       // end of market hours
        out.add(new Itch.SystemEvent(ts, GROUP, 'E', 0));       // end of system hours
        out.add(new Itch.SystemEvent(ts, GROUP, 'C', 0));       // end of messages
        return out;
    }

    /** Mid-session halt / resume, per ITCH's Trading Action message. */
    public static List<Itch.Msg> tradingAction(List<Security> instruments, long ts, char state) {
        List<Itch.Msg> out = new ArrayList<>();
        for (Security s : instruments) {
            out.add(new Itch.TradingAction(ts, s.getId(), state, ' '));
        }
        return out;
    }

    /** The [R] record: everything static about an instrument, as the exchange publishes it each morning. */
    private static Itch.OrderbookDirectory directory(Security s, long ts, int priceDecimals) {
        boolean bond = s.getAssetClass() != null && s.getAssetClass().contains("BOND");
        return new Itch.OrderbookDirectory(
                ts,
                s.getId(),
                bond ? 'Y' : 'P',                       // priced by yield, or by price
                trim(isin(s), 12),
                trim(s.getSymbol(), 32),
                "BDT",
                trim(s.getBoard() == null ? GROUP : s.getBoard(), 8),
                s.getMarketLot() == null ? 1 : s.getMarketLot(),
                QTY_TICK_TABLE,
                PRICE_TICK_TABLE,
                priceDecimals,
                0, 0,                                   // not delisted
                'N',                                    // normal market
                s.getId(),
                'L',                                    // listed
                trim(s.getSector(), 20),
                trim(s.getAssetClass(), 12),
                trim(s.getName(), 32),
                0,                                      // maturity: equities have none
                bond ? 4 : 0);
    }

    /** DSE securities carry no ISIN in this schema; derive a stable placeholder rather than send blank. */
    private static String isin(Security s) {
        return "BD" + String.format("%010d", s.getId());
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
