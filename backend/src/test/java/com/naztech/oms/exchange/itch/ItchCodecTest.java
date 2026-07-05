package com.naztech.oms.exchange.itch;

import com.naztech.oms.exchange.itch.Itch.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ITCH v2.2 wire codec: every message type round-trips (encode → decode → equal),
 * encoded length matches the spec-declared size, and the "market" price sentinel survives.
 */
class ItchCodecTest {

    private final List<Itch.Msg> samples = List.of(
            new Timestamp(34_200),
            new SystemEvent(1_000, "BOARD", 'Q', 5),
            new PriceTick(1_000, 1, 10, 100),
            new QtyTick(1_000, 2, 100, 1_000),
            new CompanyDirectory(1_000, 42, "GRAMEENPHONE", "GP"),
            new OrderbookDirectory(1_000, 5, 'U', "BD0001", "GP", "BDT", "MAIN", 1, 2, 1, 2, 0, 0,
                    'P', 42, 'A', "TELECOM", "EQTY", "Grameenphone Ltd", 0, 4),
            new TradingAction(1_000, 5, 'T', ' '),
            new AddOrder(1_000, 1_001, 'B', 500, 5, 30_000, 0),
            new AddOrderParticipant(1_000, 1_002, 'S', 300, 5, 30_500, 42, 0),
            new OrderExecuted(1_000, 1_001, 200, 9_001),
            new OrderExecutedWithPrice(1_000, 1_001, 100, 9_002, 'Y', 30_000, 5),
            new BrokenTrade(1_000, 9_001, 'S'),
            new OrderDelete(1_000, 1_002),
            new OrderReplace(1_000, 1_001, 1_003, 400, 30_100, 0),
            new Indicative(1_000, 5_000, 5, 29_900, 30_100, 30_000, 'O'),
            new Trade(1_000, 250, 5, 'Y', 30_000, 0, 9_003),
            new BestBidOffer(1_000, 5, 29_900, 1_500, 30_100, 1_200),
            new IndexValue(1_000, 900_001, 6_543_210_000L),
            new NewsItem(1_000, 5, 77, "GP", "Dividend declared", "http://dsebd.org/n/77",
                    "GP declares 60% cash dividend")
    );

    @Test
    void every_message_type_round_trips() {
        for (Itch.Msg m : samples) {
            byte[] enc = ItchCodec.encode(m);
            assertThat(enc).as("encoded size of %s", m.type()).hasSize(ItchCodec.sizeOf(m));
            Itch.Msg decoded = ItchCodec.decode(enc);
            assertThat(decoded).as("round-trip of %s", m.type()).isEqualTo(m);
        }
    }

    @Test
    void covers_the_full_itch_message_set() {
        // ITCH v2.2 defines 19 distinct message types: T S L M P R H A F E C B D U I Q O Z N
        assertThat(samples.stream().map(Itch.Msg::type).distinct().count()).isEqualTo(19);
    }

    @Test
    void market_price_sentinel_survives() {
        AddOrder mkt = new AddOrder(1_000, 2_000, 'B', 100, 5, Itch.MARKET, 0);
        AddOrder back = (AddOrder) ItchCodec.decode(ItchCodec.encode(mkt));
        assertThat(back.price()).isEqualTo(Itch.MARKET);
        assertThat(Itch.isMarket(back.price())).isTrue();
    }

    @Test
    void fixed_alpha_is_padded_and_trimmed() {
        // secCode shorter than its 12-byte field must come back without padding
        OrderbookDirectory r = (OrderbookDirectory) ItchCodec.decode(ItchCodec.encode(
                new OrderbookDirectory(1, 7, 'U', "BD0007", "BEXIMCO", "BDT", "MAIN", 1, 2, 1, 2, 0, 0,
                        'P', 7, 'A', "ENGINEERING", "EQTY", "Beximco Ltd", 0, 4)));
        assertThat(r.secCode()).isEqualTo("BEXIMCO");
        assertThat(r.securityName()).isEqualTo("Beximco Ltd");
        assertThat(r.priceDecimals()).isEqualTo(2);
    }
}
