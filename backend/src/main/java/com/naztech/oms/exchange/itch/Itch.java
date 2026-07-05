package com.naztech.oms.exchange.itch;

/**
 * DSE X-stream ITCH v2.2 message model — one immutable record per message type, matching the
 * exact fixed-width wire layout in the DSE ITCH specification (big-endian unsigned integers,
 * space-padded Alpha). Prices/yields are carried as raw integers; divide by 10^decimals
 * (from the Orderbook Directory [R]) to get the real value. See {@link ItchCodec} for the wire codec.
 */
public final class Itch {

    private Itch() {}

    /** Price/quantity sentinel: a 4-byte "market order / price unavailable" marker. */
    public static final long MARKET = 0x7FFFFFFFL;   // 2147483647
    /** Alternate "market" sentinel used by Order Replace [U] (price == -1 → 0xFFFFFFFF). */
    public static final long MARKET_ALT = 0xFFFFFFFFL;

    public static boolean isMarket(long price) {
        return price == MARKET || price == MARKET_ALT;
    }

    /** Common supertype. {@link #type()} is the 1-byte message id. */
    public interface Msg {
        char type();
    }

    // --- Session ---
    public record Timestamp(long second) implements Msg {                         // [T] 5 bytes
        public char type() { return 'T'; }
    }
    public record SystemEvent(long ts, String group, char eventCode, long orderbook) implements Msg { // [S] 18
        public char type() { return 'S'; }
    }

    // --- Reference data ---
    public record PriceTick(long ts, long tableId, long tickSize, long priceStart) implements Msg { // [L] 17
        public char type() { return 'L'; }
    }
    public record QtyTick(long ts, long tableId, long tickSize, long qtyStart) implements Msg {      // [M] 25
        public char type() { return 'M'; }
    }
    public record CompanyDirectory(long ts, long company, String name, String companyId) implements Msg { // [P] 69
        public char type() { return 'P'; }
    }
    /** [R] Orderbook Directory — 171 bytes. The key security-static record. */
    public record OrderbookDirectory(long ts, long orderbook, char priceType, String isin, String secCode,
                                     String currency, String group, long minQty, long qtyTickTableId,
                                     long priceTickTableId, int priceDecimals, long delistingDate, long delistingTime,
                                     char marketType, long company, char listingType, String sector, String instr,
                                     String securityName, long maturityDate, int yieldDecimals) implements Msg {
        public char type() { return 'R'; }
    }
    public record TradingAction(long ts, long orderbook, char tradingState, char reason) implements Msg { // [H] 11
        public char type() { return 'H'; }
    }

    // --- Order book market data ---
    public record AddOrder(long ts, long orderNumber, char verb, long quantity, long orderbook,
                           long price, long yield) implements Msg {                // [A] 34
        public char type() { return 'A'; }
    }
    public record AddOrderParticipant(long ts, long orderNumber, char verb, long quantity, long orderbook,
                                      long price, long company, long yield) implements Msg { // [F] 38
        public char type() { return 'F'; }
    }
    public record OrderExecuted(long ts, long orderNumber, long execQty, long matchNumber) implements Msg { // [E] 29
        public char type() { return 'E'; }
    }
    public record OrderExecutedWithPrice(long ts, long orderNumber, long execQty, long matchNumber,
                                         char printable, long execPrice, long execOrderbook) implements Msg { // [C] 38
        public char type() { return 'C'; }
    }
    public record BrokenTrade(long ts, long matchNumber, char reason) implements Msg {  // [B] 14
        public char type() { return 'B'; }
    }
    public record OrderDelete(long ts, long orderNumber) implements Msg {          // [D] 13
        public char type() { return 'D'; }
    }
    public record OrderReplace(long ts, long origOrderNumber, long newOrderNumber, long quantity,
                               long price, long yield) implements Msg {            // [U] 37
        public char type() { return 'U'; }
    }
    public record Indicative(long ts, long theoQty, long orderbook, long bestBid, long bestOffer,
                             long theoPrice, char crossType) implements Msg {      // [I] 30
        public char type() { return 'I'; }
    }
    public record Trade(long ts, long execQty, long orderbook, char printable, long execPrice,
                        long execYield, long matchNumber) implements Msg {         // [Q] 34
        public char type() { return 'Q'; }
    }
    public record BestBidOffer(long ts, long orderbook, long bestBid, long bestBidSize,
                               long bestOffer, long bestOfferSize) implements Msg { // [O] 33
        public char type() { return 'O'; }
    }
    public record IndexValue(long ts, long indexOrderbook, long value) implements Msg { // [Z] 17
        public char type() { return 'Z'; }
    }
    /** [N] News Item — variable length (three null-terminated Alpha fields). */
    public record NewsItem(long ts, long orderbook, long newsId, String firmId,
                           String title, String reference, String newsText) implements Msg {
        public char type() { return 'N'; }
    }

    // --- Special-case helpers (per spec section 5) ---
    /** Add Order with OrderNumber==0 && Quantity==0 is a reference-price update, not a real resting order. */
    public static boolean isReferencePrice(AddOrder a) {
        return a.orderNumber() == 0 && a.quantity() == 0;
    }
    /** Trade [Q] with MatchNumber==0 && Quantity==0 is a close-price notification, not a real trade. */
    public static boolean isClosePrice(Trade q) {
        return q.matchNumber() == 0 && q.execQty() == 0;
    }
}
