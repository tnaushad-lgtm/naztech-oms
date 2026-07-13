package com.naztech.oms.exchange.itch;

import com.naztech.oms.entity.Security;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day-start broadcast is what a broker's feed handler consumes each morning to build its world,
 * so it has to be real ITCH — not a convenient in-memory shortcut. These tests push every message
 * through the actual binary codec, which is the only way to know it would survive the wire.
 */
class ItchDayBroadcastTest {

    @Test
    @DisplayName("the open sequence announces the session, publishes the directory, then declares trading")
    void openSequenceIsInTheOrderARealFeedSendsIt() {
        List<Itch.Msg> msgs = ItchDayBroadcast.open(List.of(equity(7L, "BRACBANK"), equity(1L, "GP")), 1_752_400_000L, 2);

        String types = msgs.stream().map(m -> String.valueOf(m.type())).reduce("", String::concat);
        // T timestamp, S start-of-messages, S start-of-system-hours, L/M tick tables,
        // then (P company + R directory) per instrument, then S start-of-market-hours, then H trading.
        assertThat(types).isEqualTo("TSSLM" + "PRPR" + "S" + "HH");

        List<Itch.SystemEvent> events = msgs.stream()
                .filter(m -> m instanceof Itch.SystemEvent)
                .map(m -> (Itch.SystemEvent) m)
                .toList();
        assertThat(events).extracting(Itch.SystemEvent::eventCode)
                .containsExactly('O', 'S', 'Q');   // start of messages, system hours, market hours

        assertThat(msgs).filteredOn(m -> m instanceof Itch.TradingAction)
                .extracting(m -> ((Itch.TradingAction) m).tradingState())
                .containsOnly('T');                // every book declared open for trading
    }

    @Test
    @DisplayName("the security directory carries the instrument's static data, and survives the wire")
    void directoryRoundTripsThroughTheBinaryCodec() {
        Security s = equity(7L, "BRACBANK");
        Itch.OrderbookDirectory sent = ItchDayBroadcast.open(List.of(s), 1_752_400_000L, 2).stream()
                .filter(m -> m instanceof Itch.OrderbookDirectory)
                .map(m -> (Itch.OrderbookDirectory) m)
                .findFirst()
                .orElseThrow();

        Itch.Msg back = ItchCodec.decode(ItchCodec.encode(sent));

        assertThat(back).isInstanceOf(Itch.OrderbookDirectory.class);
        Itch.OrderbookDirectory got = (Itch.OrderbookDirectory) back;
        assertThat(got.orderbook()).isEqualTo(7L);
        assertThat(got.secCode().trim()).isEqualTo("BRACBANK");
        assertThat(got.securityName().trim()).isEqualTo("BRAC Bank PLC");
        assertThat(got.currency().trim()).isEqualTo("BDT");
        assertThat(got.priceDecimals()).isEqualTo(2);
        assertThat(got.priceType()).isEqualTo('P');       // priced, not yielded
    }

    @Test
    @DisplayName("a bond is published as yield-priced, because that is how DSE quotes it")
    void bondsArePublishedAsYieldPriced() {
        Security bond = equity(27L, "TB10Y2034");
        bond.setAssetClass("GOVT_BOND");

        Itch.OrderbookDirectory dir = ItchDayBroadcast.open(List.of(bond), 1L, 2).stream()
                .filter(m -> m instanceof Itch.OrderbookDirectory)
                .map(m -> (Itch.OrderbookDirectory) m)
                .findFirst()
                .orElseThrow();

        assertThat(dir.priceType()).isEqualTo('Y');
        assertThat(dir.yieldDecimals()).isEqualTo(4);
    }

    @Test
    @DisplayName("the close halts every book and winds the session down")
    void closeSequenceWindsTheSessionDown() {
        List<Itch.Msg> msgs = ItchDayBroadcast.close(List.of(equity(1L, "GP"), equity(7L, "BRACBANK")), 1L);

        assertThat(msgs).filteredOn(m -> m instanceof Itch.TradingAction)
                .extracting(m -> ((Itch.TradingAction) m).tradingState())
                .containsOnly('H');

        assertThat(msgs).filteredOn(m -> m instanceof Itch.SystemEvent)
                .extracting(m -> ((Itch.SystemEvent) m).eventCode())
                .containsExactly('M', 'E', 'C');   // end of market hours, system hours, messages
    }

    @Test
    @DisplayName("every message in the day-start sequence round-trips through the codec")
    void everyMessageSurvivesTheWire() {
        for (Itch.Msg m : ItchDayBroadcast.open(List.of(equity(1L, "GP")), 1_752_400_000L, 2)) {
            Itch.Msg back = ItchCodec.decode(ItchCodec.encode(m));
            assertThat(back.type()).as("message %s did not survive encode/decode", m.type()).isEqualTo(m.type());
        }
    }

    private static Security equity(Long id, String symbol) {
        Security s = new Security();
        s.setId(id);
        s.setSymbol(symbol);
        s.setName(symbol.equals("BRACBANK") ? "BRAC Bank PLC" : symbol + " Ltd.");
        s.setAssetClass("EQUITY_MAIN");
        s.setBoard("MAIN");
        s.setSector("Bank");
        s.setStatus("ACTIVE");
        s.setLotSize(1);
        s.setMarketLot(1);
        s.setTickSize(new BigDecimal("0.10"));
        return s;
    }
}
