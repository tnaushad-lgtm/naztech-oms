package com.naztech.oms.service;

import java.util.List;

/**
 * Every screen and feature in the OMS, described the way a person would ask for it.
 *
 * <p>This exists so a dealer can ask "where do I find market depth?" or "how do I place a buy order?"
 * and be sent to the right screen — rather than being told what an order type is, which is what the
 * advisor did before: an answer shaped like an answer, to a question nobody asked.
 *
 * <p>The {@code aliases} are the point. They are not keywords for a search box; they are the words a
 * trader actually uses — "order book", "bid ask queue", "buy sell option", "5 level depth" — and they
 * are what the embedding model matches against. A feature nobody can name is a feature nobody finds.
 */
public final class FeatureCatalog {

    /**
     * @param route  where it lives
     * @param title  what it is called in the navigation
     * @param what   one line: what the screen does
     * @param how    one line: how to use it, concretely enough to act on
     * @param aliases the words a trader would use when looking for it
     */
    public record Feature(String route, String title, String what, String how, String aliases) {
        /** The text the embedding model indexes. */
        public String searchText() {
            return title + " · " + what + " · " + aliases;
        }
    }

    public static final List<Feature> FEATURES = List.of(

            new Feature("/depth", "Market Depth Analysis",
                    "The full order book for an instrument: every bid and offer level, cumulative size, the depth curve, spread, microprice and book imbalance.",
                    "Open Market Depth from the sidebar, pick an instrument on the left, and choose 10, 20 or 50 levels. Click any price to load it into the order ticket.",
                    "market depth, order book, depth of market, DOM, bid ask ladder, buy sell queue, level 2, book pressure, imbalance, spread, liquidity, depth chart, five level depth"),

            new Feature("/terminal", "Trader Terminal",
                    "The main trading screen: market watch, chart, depth ladder, order ticket and the order blotter in one place.",
                    "Pick a security in Market Watch on the left, then use the Order Ticket on the right: press BUY or SELL and the full order window opens — quantity, price, validity and the pre-trade risk check.",
                    "buy, sell, place an order, order entry, order ticket, trade screen, terminal, where do I buy, how to sell shares, limit order, market order, order pad"),

            // The blotter, the watchlist and the ticket all live on the Trader Terminal — but a dealer
            // asks for them by name, not by the screen that happens to host them. One entry per screen
            // meant "where is the order blotter?" came back with instructions for buying, which is an
            // answer to a question nobody asked.
            new Feature("/terminal", "Order Blotter",
                    "Every order you have placed today: time, symbol, side, quantity, price, filled quantity, status and its AI risk score.",
                    "It runs along the bottom of the Trader Terminal. Cancel a working order with the ✕ on its row, or amend it with the pencil. It is also a dockable panel and a dashboard widget.",
                    "order blotter, blotter, my orders, order list, order status, order history, pending orders, working orders, open orders, cancel an order, amend an order, modify order, did my order fill, order log"),

            new Feature("/terminal", "Market Watch & watchlists",
                    "The live instrument list on the left of the terminal: last price, change and volume, with your own watchlists.",
                    "Click any row to load that instrument into the chart, depth and ticket. Use the + button to create a watchlist and the ★ to add the selected instrument to it.",
                    "market watch, watchlist, my list, favourites, add to watchlist, instrument list, ticker list, quotes list, follow a stock"),

            new Feature("/workspace", "Trading Desk",
                    "The same trading panels as the terminal, but dockable: drag tabs to rearrange, drag borders to resize, maximise or close any panel.",
                    "Drag a tab header to move a panel, drag the splitters to resize, use the ⛶ button to maximise. Your layout is remembered. 'Reset layout' puts it back.",
                    "dockable, docking, rearrange panels, resize windows, move panels, workspace, custom layout, multi panel, maximise, minimise, drag and drop panels"),

            new Feature("/dashboard", "My Dashboard",
                    "A configurable dashboard of widgets: charts, gainers and losers, breadth, allocation, P&L, risk alerts and more.",
                    "Drag a widget by its title bar, resize from the corner, minimise or hide it from the widget header. Add widgets from the widget picker and save the arrangement as a preset.",
                    "dashboard, widgets, home screen, my screen, customise, presets, gainers losers, breadth, KPI"),

            new Feature("/order-bot", "AI Order Bot",
                    "Place orders by typing or speaking them in plain English or Bangla — the OMS parses the intent into a validated order for you to confirm.",
                    "Type or say something like 'buy 500 GP at 305' (or in Bangla), review the parsed order, then confirm.",
                    "voice order, natural language order, bangla order, speak an order, AI order, order bot, type an order"),

            new Feature("/screener", "Market Screener",
                    "Filter and sort the whole market by price, change, volume, sector, asset class or share category.",
                    "Set the filters at the top, sort by any column, and add anything interesting to a watchlist.",
                    "screener, filter stocks, find shares, sort by volume, scan the market, stock finder, share category A B N Z"),

            new Feature("/heatmap", "Market Heatmap",
                    "The whole market as a treemap coloured by change — sector blocks sized by turnover.",
                    "Hover a tile to see the instrument; click it to open it.",
                    "heatmap, treemap, market map, sector performance, what is up today, colour map"),

            new Feature("/tape", "Trade Tape",
                    "Time and sales: every trade print as it happens, with price, size and side.",
                    "Open Trade Tape from the sidebar. Filter by instrument to follow one name.",
                    "tape, time and sales, prints, executions, trade log, last trades, ticker"),

            new Feature("/portfolio", "Portfolio",
                    "Holdings, average cost, market value, unrealised and realised P&L, and allocation by sector and asset class.",
                    "Choose the account at the top. The equity curve shows P&L over time.",
                    "portfolio, holdings, positions, P&L, profit and loss, my shares, allocation, equity curve, cost basis"),

            new Feature("/alerts", "Price Alerts",
                    "Alerts that fire when an instrument crosses a price you choose.",
                    "Create an alert with a symbol, a direction (above/below) and a price. It pops up on screen when it triggers.",
                    "price alert, notification, alarm, notify me, tell me when, price trigger"),

            new Feature("/rms", "Risk Management (RMS)",
                    "Pre-trade risk: per-client, per-trader and per-broker limits, the wash-trade control, the AI risk alert feed, and the broker kill-switch.",
                    "Edit a limit in the table and save the row. The kill-switch halts all new orders for a broker at once.",
                    "risk, RMS, limits, credit control, buying power limit, kill switch, halt broker, wash trade, order value limit, exposure"),

            new Feature("/reports", "Reports & EOD Export",
                    "End-of-day reports and exports: orders, trades and positions.",
                    "Pick the report and the date, then export.",
                    "reports, export, end of day, EOD, download, statement, CSV"),

            new Feature("/admin", "Exchange Admin — Market Session",
                    "The exchange control plane, including the Market Session: Start Market, the opening bell, Halt, Resume, Close and Reset.",
                    "The Market Session card is at the top. DSE trades 10:00–14:30 (Asia/Dhaka). Start Market opens pre-open (orders rest but do not trade); the opening bell begins continuous trading.",
                    "open the market, close the market, start market, market session, trading hours, halt trading, pre-open, opening bell, market closed, why are orders rejected, exchange admin, onboard broker"),

            new Feature("/admin/connectivity", "Exchange Link",
                    "The FIX order-entry session and the ITCH market-data feed: connection state, sequence numbers, a test order, and the throughput test.",
                    "The FIX session state is on the left, ITCH on the right. The Throughput Test card measures how many orders per second this deployment sustains.",
                    "FIX session, connectivity, exchange link, ITCH, logon, connect to exchange, throughput, load test, orders per second, performance"),

            new Feature("/admin/fix", "FIX Monitor",
                    "Every FIX message in and out, decoded and filterable.",
                    "Filter by message type or search the raw text to trace an order across the session.",
                    "FIX messages, FIX log, message monitor, execution report, new order single, wire log, debug FIX"));

    private FeatureCatalog() {
    }
}
