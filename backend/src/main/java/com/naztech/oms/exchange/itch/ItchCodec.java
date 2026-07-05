package com.naztech.oms.exchange.itch;

import com.naztech.oms.exchange.itch.Itch.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Binary codec for DSE ITCH v2.2 messages — big-endian, fixed-width (per the DSE ITCH spec),
 * with null-terminated Alpha for the News item. {@link #encode} produces the exact wire bytes
 * (used by {@link ItchSimulator}); {@link #decode} parses real or simulated bytes back to {@link Itch.Msg}.
 * The two are exact inverses (verified by round-trip tests).
 */
public final class ItchCodec {

    private ItchCodec() {}

    // ------------------------------------------------------------------ decode
    public static Itch.Msg decode(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);            // big-endian by default
        char type = (char) (b.get() & 0xFF);
        return switch (type) {
            case 'T' -> new Timestamp(u32(b));
            case 'S' -> new SystemEvent(u32(b), alpha(b, 8), ch(b), u32(b));
            case 'L' -> new PriceTick(u32(b), u32(b), u32(b), u32(b));
            case 'M' -> new QtyTick(u32(b), u32(b), u64(b), u64(b));
            case 'P' -> new CompanyDirectory(u32(b), u32(b), alpha(b, 30), alpha(b, 30));
            case 'R' -> new OrderbookDirectory(u32(b), u32(b), ch(b), alpha(b, 12), alpha(b, 12),
                    alpha(b, 3), alpha(b, 8), u64(b), u32(b), u32(b), (int) u32(b), u32(b), u32(b),
                    ch(b), u32(b), ch(b), alpha(b, 12), alpha(b, 12), alpha(b, 60), u32(b), (int) u32(b));
            case 'H' -> new TradingAction(u32(b), u32(b), ch(b), ch(b));
            case 'A' -> new AddOrder(u32(b), u64(b), ch(b), u64(b), u32(b), u32(b), u32(b));
            case 'F' -> new AddOrderParticipant(u32(b), u64(b), ch(b), u64(b), u32(b), u32(b), u32(b), u32(b));
            case 'E' -> new OrderExecuted(u32(b), u64(b), u64(b), u64(b));
            case 'C' -> new OrderExecutedWithPrice(u32(b), u64(b), u64(b), u64(b), ch(b), u32(b), u32(b));
            case 'B' -> new BrokenTrade(u32(b), u64(b), ch(b));
            case 'D' -> new OrderDelete(u32(b), u64(b));
            case 'U' -> new OrderReplace(u32(b), u64(b), u64(b), u64(b), u32(b), u32(b));
            case 'I' -> new Indicative(u32(b), u64(b), u32(b), u32(b), u32(b), u32(b), ch(b));
            case 'Q' -> new Trade(u32(b), u64(b), u32(b), ch(b), u32(b), u32(b), u64(b));
            case 'O' -> new BestBidOffer(u32(b), u32(b), u32(b), u64(b), u32(b), u64(b));
            case 'Z' -> new IndexValue(u32(b), u32(b), u64(b));
            case 'N' -> new NewsItem(u32(b), u32(b), u32(b), alpha(b, 30), cstr(b), cstr(b), cstr(b));
            default -> throw new IllegalArgumentException("Unknown ITCH message type: '" + type + "'");
        };
    }

    // ------------------------------------------------------------------ encode
    public static byte[] encode(Itch.Msg m) {
        ByteBuffer b = ByteBuffer.allocate(sizeOf(m));
        b.put((byte) m.type());
        switch (m.type()) {
            case 'T' -> { Timestamp x = (Timestamp) m; p32(b, x.second()); }
            case 'S' -> { SystemEvent x = (SystemEvent) m; p32(b, x.ts()); pa(b, x.group(), 8); pc(b, x.eventCode()); p32(b, x.orderbook()); }
            case 'L' -> { PriceTick x = (PriceTick) m; p32(b, x.ts()); p32(b, x.tableId()); p32(b, x.tickSize()); p32(b, x.priceStart()); }
            case 'M' -> { QtyTick x = (QtyTick) m; p32(b, x.ts()); p32(b, x.tableId()); p64(b, x.tickSize()); p64(b, x.qtyStart()); }
            case 'P' -> { CompanyDirectory x = (CompanyDirectory) m; p32(b, x.ts()); p32(b, x.company()); pa(b, x.name(), 30); pa(b, x.companyId(), 30); }
            case 'R' -> { OrderbookDirectory x = (OrderbookDirectory) m; p32(b, x.ts()); p32(b, x.orderbook()); pc(b, x.priceType()); pa(b, x.isin(), 12);
                pa(b, x.secCode(), 12); pa(b, x.currency(), 3); pa(b, x.group(), 8); p64(b, x.minQty());
                p32(b, x.qtyTickTableId()); p32(b, x.priceTickTableId()); p32(b, x.priceDecimals()); p32(b, x.delistingDate());
                p32(b, x.delistingTime()); pc(b, x.marketType()); p32(b, x.company()); pc(b, x.listingType());
                pa(b, x.sector(), 12); pa(b, x.instr(), 12); pa(b, x.securityName(), 60); p32(b, x.maturityDate()); p32(b, x.yieldDecimals()); }
            case 'H' -> { TradingAction x = (TradingAction) m; p32(b, x.ts()); p32(b, x.orderbook()); pc(b, x.tradingState()); pc(b, x.reason()); }
            case 'A' -> { AddOrder x = (AddOrder) m; p32(b, x.ts()); p64(b, x.orderNumber()); pc(b, x.verb()); p64(b, x.quantity());
                p32(b, x.orderbook()); p32(b, x.price()); p32(b, x.yield()); }
            case 'F' -> { AddOrderParticipant x = (AddOrderParticipant) m; p32(b, x.ts()); p64(b, x.orderNumber()); pc(b, x.verb()); p64(b, x.quantity());
                p32(b, x.orderbook()); p32(b, x.price()); p32(b, x.company()); p32(b, x.yield()); }
            case 'E' -> { OrderExecuted x = (OrderExecuted) m; p32(b, x.ts()); p64(b, x.orderNumber()); p64(b, x.execQty()); p64(b, x.matchNumber()); }
            case 'C' -> { OrderExecutedWithPrice x = (OrderExecutedWithPrice) m; p32(b, x.ts()); p64(b, x.orderNumber()); p64(b, x.execQty()); p64(b, x.matchNumber());
                pc(b, x.printable()); p32(b, x.execPrice()); p32(b, x.execOrderbook()); }
            case 'B' -> { BrokenTrade x = (BrokenTrade) m; p32(b, x.ts()); p64(b, x.matchNumber()); pc(b, x.reason()); }
            case 'D' -> { OrderDelete x = (OrderDelete) m; p32(b, x.ts()); p64(b, x.orderNumber()); }
            case 'U' -> { OrderReplace x = (OrderReplace) m; p32(b, x.ts()); p64(b, x.origOrderNumber()); p64(b, x.newOrderNumber()); p64(b, x.quantity());
                p32(b, x.price()); p32(b, x.yield()); }
            case 'I' -> { Indicative x = (Indicative) m; p32(b, x.ts()); p64(b, x.theoQty()); p32(b, x.orderbook()); p32(b, x.bestBid());
                p32(b, x.bestOffer()); p32(b, x.theoPrice()); pc(b, x.crossType()); }
            case 'Q' -> { Trade x = (Trade) m; p32(b, x.ts()); p64(b, x.execQty()); p32(b, x.orderbook()); pc(b, x.printable());
                p32(b, x.execPrice()); p32(b, x.execYield()); p64(b, x.matchNumber()); }
            case 'O' -> { BestBidOffer x = (BestBidOffer) m; p32(b, x.ts()); p32(b, x.orderbook()); p32(b, x.bestBid()); p64(b, x.bestBidSize());
                p32(b, x.bestOffer()); p64(b, x.bestOfferSize()); }
            case 'Z' -> { IndexValue x = (IndexValue) m; p32(b, x.ts()); p32(b, x.indexOrderbook()); p64(b, x.value()); }
            case 'N' -> { NewsItem x = (NewsItem) m; p32(b, x.ts()); p32(b, x.orderbook()); p32(b, x.newsId()); pa(b, x.firmId(), 30);
                pcstr(b, x.title()); pcstr(b, x.reference()); pcstr(b, x.newsText()); }
            default -> throw new IllegalArgumentException("Unknown ITCH message: " + m);
        }
        return b.array();
    }

    /** Byte length of a message on the wire. */
    public static int sizeOf(Itch.Msg m) {
        return switch (m.type()) {
            case 'T' -> 5;
            case 'S' -> 18;
            case 'L' -> 17;
            case 'M' -> 25;
            case 'P' -> 69;
            case 'R' -> 171;
            case 'H' -> 11;
            case 'A' -> 34;
            case 'F' -> 38;
            case 'E' -> 29;
            case 'C' -> 38;
            case 'B' -> 14;
            case 'D' -> 13;
            case 'U' -> 37;
            case 'I' -> 30;
            case 'Q' -> 34;
            case 'O' -> 33;
            case 'Z' -> 17;
            case 'N' -> {
                NewsItem x = (NewsItem) m;
                yield 1 + 4 + 4 + 4 + 30 + bytes(x.title()) + 1 + bytes(x.reference()) + 1 + bytes(x.newsText()) + 1;
            }
            default -> throw new IllegalArgumentException("Unknown ITCH message: " + m);
        };
    }

    // ------------------------------------------------------------------ primitives
    private static long u32(ByteBuffer b) { return b.getInt() & 0xFFFFFFFFL; }
    private static long u64(ByteBuffer b) { return b.getLong(); }
    private static char ch(ByteBuffer b) { return (char) (b.get() & 0xFF); }
    private static String alpha(ByteBuffer b, int len) {
        byte[] a = new byte[len];
        b.get(a);
        int end = len;
        while (end > 0 && (a[end - 1] == ' ' || a[end - 1] == 0)) end--;   // strip right pad + nulls
        return new String(a, 0, end, StandardCharsets.US_ASCII);
    }
    private static String cstr(ByteBuffer b) {
        StringBuilder sb = new StringBuilder();
        while (b.hasRemaining()) {
            int c = b.get() & 0xFF;
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString();
    }

    private static void p32(ByteBuffer b, long v) { b.putInt((int) v); }
    private static void p64(ByteBuffer b, long v) { b.putLong(v); }
    private static void pc(ByteBuffer b, char c) { b.put((byte) c); }
    private static void pa(ByteBuffer b, String s, int len) {
        byte[] a = s == null ? new byte[0] : s.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(a.length, len);
        b.put(a, 0, n);
        for (int i = n; i < len; i++) b.put((byte) ' ');                    // right-pad with spaces
    }
    private static void pcstr(ByteBuffer b, String s) {
        if (s != null) b.put(s.getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 0);
    }
    private static int bytes(String s) { return s == null ? 0 : s.getBytes(StandardCharsets.US_ASCII).length; }
}
