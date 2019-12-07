package DevTrader;

import AutoTraderOld.XuTraderHelper;
import api.OrderAugmented;
import enums.Currency;
import api.TradingConstants;
import auxiliary.SimpleBar;
import client.*;
import controller.ApiController;
import enums.Direction;
import handler.DefaultConnectionHandler;
import handler.LiveHandler;
import net.bytebuddy.pool.TypePool;
import utility.TradingUtility;
import utility.Utility;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static api.ControllerCalls.placeOrModifyOrderCheck;
import static client.Types.TimeInForce.*;
import static enums.AutoOrderType.*;
import static utility.TradingUtility.*;
import static utility.Utility.*;

public class BreachTrader implements LiveHandler, ApiController.IPositionHandler {

    private static final String HEDGER_INDEX = "MES";

    static final int MAX_ATTEMPTS = 100;
    private static final int MAX_CROSS_PER_MONTH = 2;
    private static final double MAX_ENTRY_DEV = 0.02; //was 0.02 pre 19/11/11
    private static final double MIN_ENTRY_DEV = 0.002; //was 0.002 pre 19/11/11
    private static final double ENTRY_CUSHION = 0.0;
//    private static final double PRICE_OFFSET_PERC = 0.002;

    private static volatile AtomicBoolean INDEX_BULL_YEAR = new AtomicBoolean(true);
    private static volatile AtomicBoolean INDEX_BULL_MONTH = new AtomicBoolean(true);
    private static volatile AtomicBoolean INDEX_BULL_DAY = new AtomicBoolean(true);
    private static volatile AtomicBoolean TRIPLE_BULL = new AtomicBoolean(true);
    private static volatile AtomicBoolean TRIPLE_BEAR = new AtomicBoolean(true);


    static volatile NavigableMap<Integer, OrderAugmented> devOrderMap = new ConcurrentSkipListMap<>();
    static volatile AtomicInteger devTradeID = new AtomicInteger(100);

    private static DateTimeFormatter f = DateTimeFormatter.ofPattern("M-d H:mm:ss");
    private static final DateTimeFormatter f1 = DateTimeFormatter.ofPattern("M-d H:mm");
    public static final DateTimeFormatter f2 = DateTimeFormatter.ofPattern("M-d H:mm:s.SSS");


    private static double totalDelta = 0.0;
    private static double totalAbsDelta = 0.0;
    private static double longDelta = 0.0;
    private static double shortDelta = 0.0;
    private static ApiController apDev;

    private static final LocalDate LAST_MONTH_DAY = getMonthBeginMinus1Day();
    private static final LocalDate LAST_YEAR_DAY = getYearBeginMinus1Day();

    private static volatile AtomicInteger ibStockReqId = new AtomicInteger(60000);
    private static File devOutput = new File(TradingConstants.GLOBALPATH + "breachMDev.txt");
    private static File startEndTime = new File(TradingConstants.GLOBALPATH + "startEndTime.txt");

    private static ScheduledExecutorService es = Executors.newScheduledThreadPool(10);


    private static final double HI_LIMIT = 800000.0;
    private static final double LO_LIMIT = -800000.0;
    private static final double HEDGE_THRESHOLD = 100000.0;
    private static final double MAX_DELTA_PER_TRADE = 200000;
    //private static final double ABS_LIMIT = 5000000.0;

    public static Map<Currency, Double> fx = new HashMap<>();
    private static Map<String, Double> multi = new HashMap<>();
    //private static Map<String, Double> defaultSize = new HashMap<>();

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDate, SimpleBar>>
            ytdDayData = new ConcurrentSkipListMap<>(String::compareTo);

    private static volatile ConcurrentSkipListMap<String, ConcurrentSkipListMap<LocalDateTime, Double>>
            liveData = new ConcurrentSkipListMap<>();

    private volatile static Map<Contract, Double> contractPosMap =
            new ConcurrentSkipListMap<>(Comparator.comparing(Utility::ibContractToSymbol));

    private volatile static Map<String, Double> symbolPosMap = new ConcurrentSkipListMap<>(String::compareTo);

    private static Map<String, Double> lastMap = new ConcurrentHashMap<>();
    private static Map<String, Double> bidMap = new ConcurrentHashMap<>();
    private static Map<String, Double> askMap = new ConcurrentHashMap<>();
    private static volatile Map<String, AtomicBoolean> addedMap = new ConcurrentHashMap<>();
    private static volatile Map<String, AtomicBoolean> liquidatedMap = new ConcurrentHashMap<>();

    static double getLast(String symb) {
        return lastMap.getOrDefault(symb, 0.0);
    }

    private static double getMaxEntryDev(LocalDate today, double maxEntryDev) {
        return Math.max(0.005, maxEntryDev * Math.pow(0.8, (today.getDayOfMonth() - 1)));
    }

    static double getBid(String symb) {
        return bidMap.getOrDefault(symb, 0.0);
    }

    static double getAsk(String symb) {
        return askMap.getOrDefault(symb, 0.0);
    }

    private static Semaphore histSemaphore = new Semaphore(45);

    private BreachTrader() {
        outputToFile(str("Starting Trader ", LocalDateTime.now()), startEndTime);

        String line;
        try (BufferedReader reader1 = new BufferedReader(new InputStreamReader(
                new FileInputStream(TradingConstants.GLOBALPATH + "fx.txt")))) {
            while ((line = reader1.readLine()) != null) {
                List<String> al1 = Arrays.asList(line.split("\t"));
                fx.put(Currency.get(al1.get(0)), Double.parseDouble(al1.get(1)));
            }
        } catch (IOException x) {
            x.printStackTrace();
        }

        try (BufferedReader reader1 = new BufferedReader(new InputStreamReader(
                new FileInputStream(TradingConstants.GLOBALPATH + "multiplier.txt")))) {
            while ((line = reader1.readLine()) != null) {
                List<String> al1 = Arrays.asList(line.split("\t"));
                multi.put(al1.get(0), Double.parseDouble(al1.get(1)));
            }
        } catch (IOException x) {
            x.printStackTrace();
        }

//        try (BufferedReader reader1 = new BufferedReader(new InputStreamReader(
//                new FileInputStream(TradingConstants.GLOBALPATH + "defaultNonUSSize.txt")))) {
//            while ((line = reader1.readLine()) != null) {
//                List<String> al1 = Arrays.asList(line.split("\t"));
//                defaultSize.put(al1.get(0), Double.parseDouble(al1.get(1)));
//            }
//        } catch (IOException x) {
//            x.printStackTrace();
//        }

        registerContract(getActiveA50Contract());
//        registerContract(getActiveBTCContract());
        registerContract(getActiveMNQContract());
        registerContract(getActiveMESContract());


        try (BufferedReader reader1 = new BufferedReader(new InputStreamReader(
                new FileInputStream(TradingConstants.GLOBALPATH + "breachUSNames.txt")))) {
            while ((line = reader1.readLine()) != null) {
                List<String> al1 = Arrays.asList(line.split("\t"));
                registerContract(getUSStockContract(al1.get(0)));
                //defaultSize.put(al1.get(0), Double.parseDouble(al1.get(1)));
            }
        } catch (IOException x) {
            x.printStackTrace();
        }
    }

//    static double getDefaultSize(String symb) {
//        if (lastMap.getOrDefault(symb, 0.0) != 0.0) {
//            double last = lastMap.get(symb);
//            return (int) (Math.round(90000.0 / last / 100.0)) * 100;
//        }
////        if (defaultSize.containsKey(symb)) {
////            return defaultSize.get(symb);
////        }
////        outputToSpecial(str(LocalDateTime.now(), symb, "no default size "));
//        return 0.0;
//    }

    private static void registerContract(Contract ct) {
        contractPosMap.put(ct, 0.0);
        symbolPosMap.put(ibContractToSymbol(ct), 0.0);
        String symbol = ibContractToSymbol(ct);
        if (!liveData.containsKey(symbol)) {
            liveData.put(symbol, new ConcurrentSkipListMap<>());
        }
    }

    private static void registerContractPosition(Contract ct, double pos) {
        contractPosMap.put(ct, pos);
        symbolPosMap.put(ibContractToSymbol(ct), pos);
        String symbol = ibContractToSymbol(ct);
        if (!liveData.containsKey(symbol)) {
            liveData.put(ibContractToSymbol(ct), new ConcurrentSkipListMap<>());
        }
    }


    private void connectAndReqPos() {
        ApiController ap = new ApiController(
                new DefaultConnectionHandler(),
                new DefaultLogger(), new DefaultLogger());
        apDev = ap;
        CountDownLatch l = new CountDownLatch(1);
        boolean connectionStatus = false;

        try {
            pr(" using port 4001");
            ap.connect("127.0.0.1", 4001, 5, "");
            connectionStatus = true;
            l.countDown();
            pr(" Latch counted down 4001 " + LocalTime.now());
        } catch (IllegalStateException ex) {
            pr(" illegal state exception caught ", ex);
        }

        if (!connectionStatus) {
            pr(" using port 7496");
            ap.connect("127.0.0.1", 7496, 5, "");
            l.countDown();
            pr(" Latch counted down 7496" + LocalTime.now());
        }

        try {
            l.await();
            ap.setConnected();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        pr(" Time after latch released " + LocalTime.now());
        Executors.newScheduledThreadPool(10).schedule(() -> reqHoldings(ap),
                500, TimeUnit.MILLISECONDS);
    }


    private void reqHoldings(ApiController ap) {
        pr("req holdings ");
        ap.reqPositions(this);
    }


    private static void ytdOpen(Contract c, String date, double open, double high, double low,
                                double close, long volume) {

        String symbol = utility.Utility.ibContractToSymbol(c);
        if (!date.startsWith("finished")) {
            LocalDate ld = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
            ytdDayData.get(symbol).put(ld, new SimpleBar(open, high, low, close));
        } else {
            if (!ytdDayData.get(symbol).firstKey().isBefore(LAST_YEAR_DAY)) {
                pr("check YtdOpen", symbol, ytdDayData.get(symbol).firstKey());
            }
            histSemaphore.release(1);
        }
    }

    static double getLivePos(String symb) {
        if (symbolPosMap.containsKey(symb)) {
            return symbolPosMap.get(symb);
        }
        outputToSpecial(str(LocalTime.now(), " get live pos from breach dev not found ", symb));
        return 0.0;
    }

    @Override
    public void position(String account, Contract contract, double position, double avgCost) {
        String symbol = ibContractToSymbol(contract);
        if (!contract.symbol().equals("USD") &&
                !symbol.equalsIgnoreCase("SGXA50PR") &&
                (position != 0 || symbolPosMap.getOrDefault(symbol, 0.0) != 0.0)) {
            pr("non-0 position ", symbol, position);
            registerContractPosition(contract, position);
        }
    }

    @Override
    public void positionEnd() {
        contractPosMap.keySet().stream().filter(e -> e.secType() == Types.SecType.FUT).forEach(c -> {
            String symb = ibContractToSymbol(c);
            pr(" symbol in positionEnd fut", symb);
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());
            CompletableFuture.runAsync(() -> {
                try {
                    histSemaphore.acquire();

                    reqHistDayData(apDev, ibStockReqId.addAndGet(5),
                            histCompatibleCt(c), BreachTrader::ytdOpen,
                            getCalendarYtdDays() + 10, Types.BarSize._1_day);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });

            es.schedule(() -> {
                pr("Position end: requesting live for fut:", symb);
                req1ContractLive(apDev, liveCompatibleCt(c), this, false);
            }, 10L, TimeUnit.SECONDS);
        });

        contractPosMap.keySet().stream().filter(e -> e.secType() != Types.SecType.FUT).forEach(c -> {
            String symb = ibContractToSymbol(c);
            pr(" symbol in positionEnd non fut", symb);
            ytdDayData.put(symb, new ConcurrentSkipListMap<>());

            if (!symb.equals("USD")) {
                CompletableFuture.runAsync(() -> {
                    try {
                        histSemaphore.acquire();
                        reqHistDayData(apDev, ibStockReqId.addAndGet(5),
                                histCompatibleCt(c), BreachTrader::ytdOpen,
                                getCalendarYtdDays() + 10, Types.BarSize._1_day);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                });
            }
            es.schedule(() -> {
                pr("Position end: requesting live for nonFut:", symb);
                req1ContractLive(apDev, liveCompatibleCt(c), this, false);
            }, 10L, TimeUnit.SECONDS);
        });
    }

    private static int getCalendarYtdDays() {
        return (int) ChronoUnit.DAYS.between(LAST_YEAR_DAY, LocalDate.now());
    }

    static double getDefaultSize(Contract ct, double last, LocalDate t) {
        if (last != 0.0) {
            if ((ct.secType() == Types.SecType.FUT || ct.secType() == Types.SecType.CONTFUT)
                    && ct.currency().equalsIgnoreCase("USD")) {
                double delta = 90000.0 * Math.pow(0.8, t.getDayOfMonth() - 1);
                double multiplier = multi.get(ibContractToSymbol(ct));
                return Math.max(1, Math.min(10, (int) (delta / last / multiplier)));
            } else if (ct.secType() == Types.SecType.STK && ct.currency().equalsIgnoreCase("USD")) {
                double delta = 90000.0 * Math.pow(0.8, t.getDayOfMonth() - 1);
                return Math.max(100, (int) (Math.round(delta / last / 100.0)) * 100);
            } else {
                throw new IllegalStateException(str("unknown contract ", ct.symbol(),
                        ct.secType(), ct.currency(), last, t));
            }
        }
        throw new IllegalStateException(str(ibContractToSymbol(ct), " no default size "));
    }

    static double getDefaultSize(Contract ct, double last, LocalDate t, Map<String, Double> multiMap) {
        if (last != 0.0) {
            if ((ct.secType() == Types.SecType.FUT || ct.secType() == Types.SecType.CONTFUT)
                    && ct.currency().equalsIgnoreCase("USD")) {
                double delta = 90000.0 * Math.pow(0.8, t.getDayOfMonth() - 1);
                double multiplier = multiMap.get(ibContractToSymbol(ct));
                return Math.max(1, Math.min(10, (int) (delta / last / multiplier)));
            } else if (ct.secType() == Types.SecType.STK && ct.currency().equalsIgnoreCase("USD")) {
                double delta = 90000.0 * Math.pow(0.8, t.getDayOfMonth() - 1);
                return Math.max(100, (int) (Math.round(delta / last / 100.0)) * 100);
                //return 100.0;
            } else {
                throw new IllegalStateException(str("unknown contract ", ct.symbol(),
                        ct.secType(), ct.currency(), last, t));
            }
        }
        throw new IllegalStateException(str(ibContractToSymbol(ct), " no default size "));
    }

    private static double getDelta(Contract ct, double price, double size, double fx) {

        if (ct.secType() == Types.SecType.STK) {
            return price * size * fx;
        } else if (ct.secType() == Types.SecType.FUT) {
            if (multi.containsKey(ibContractToSymbol(ct))) {
                return price * size * fx * multi.get(ibContractToSymbol(ct));
            } else {
                outputToSymbolFile(ibContractToSymbol(ct), str("no multi", ibContractToSymbol(ct),
                        price, size, fx), devOutput);
                throw new IllegalStateException(str("no multiplier", ibContractToSymbol(ct)));
            }
        }
        outputToSymbolFile(ibContractToSymbol(ct), str(" cannot get delta for symbol type"
                , ct.symbol(), ct.secType()), devOutput);
        throw new IllegalStateException(str(" cannot get delta for symbol type", ct.symbol(), ct.secType()));
    }

    private static void breachAdder(Contract ct, double price, LocalDateTime t, double yOpen, double mOpen,
                                    double dOpen) {
        String symbol = ibContractToSymbol(ct);
        LocalDate prevMonthDay = getPrevMonthCutoff(ct, LAST_MONTH_DAY);
        double pos = symbolPosMap.get(symbol);
//        double defaultS;
//        if (defaultSize.containsKey(symbol)) {
//            defaultS = defaultSize.get(symbol);
//        } else {
//            defaultS = getDefaultSize(ct);
//        }

        double defaultS = getDefaultSize(ct, price, t.toLocalDate());

        double prevClose = getLastPriceFromYtd(ct);

        boolean added = addedMap.containsKey(symbol) && addedMap.get(symbol).get();
        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();

        long numCrosses = ytdDayData.get(symbol).entrySet().stream()
                .filter(e -> e.getKey().isAfter(prevMonthDay))
                .filter(e -> e.getValue().includes(mOpen))
                .count();

        if (!added && !liquidated && pos == 0.0 && prevClose != 0.0 && numCrosses <= MAX_CROSS_PER_MONTH) {

            if (price > yOpen && price > mOpen && price > dOpen
                    && totalDelta < HI_LIMIT
                    && longDelta < HI_LIMIT
                    && ((price / Math.max(yOpen, mOpen) - 1) < getMaxEntryDev(LocalDate.now(), MAX_ENTRY_DEV))
                    && ((price / Math.max(yOpen, mOpen) - 1) > MIN_ENTRY_DEV)
                    && TRIPLE_BULL.get()) {

                addedMap.put(symbol, new AtomicBoolean(true));
                int id = devTradeID.incrementAndGet();
                double bidPrice = r(Math.min(price, bidMap.getOrDefault(symbol, price)) -
                        r(ENTRY_CUSHION * price));

                bidPrice = roundToMinVariation(symbol, Direction.Long, bidPrice);

                Order o = placeBidLimitTIF(bidPrice, defaultS, DAY);
                if (checkDeltaImpact(ct, o)) {
                    devOrderMap.put(id, new OrderAugmented(ct, t, o, BREACH_ADDER));
                    placeOrModifyOrderCheck(apDev, ct, o, new PatientDevHandler(id));
                    outputToSymbolFile(symbol, str("********", t.format(f1)), devOutput);
                    outputToSymbolFile(symbol, str(o.orderId(), id, "ADDER BUY:",
                            devOrderMap.get(id), "yOpen:" + yOpen, "mOpen:" + mOpen, "numCross", numCrosses,
                            "prevClose", prevClose, "p/b/a", price, getBid(symbol), getAsk(symbol),
                            "MAX Entry Dev:", getMaxEntryDev(LocalDate.now(), MAX_ENTRY_DEV)
                            , "devFromMaxOpen", r10000(price / Math.max(yOpen, mOpen) - 1))
                            , devOutput);
                }
            } else if (price < yOpen && price < mOpen && price < dOpen
                    && totalDelta > LO_LIMIT
                    && shortDelta > LO_LIMIT
                    && (price / Math.min(yOpen, mOpen) - 1) > -getMaxEntryDev(LocalDate.now(), MAX_ENTRY_DEV)
                    && (price / Math.min(yOpen, mOpen) - 1) < -MIN_ENTRY_DEV
                    && TRIPLE_BEAR.get()) {

                addedMap.put(symbol, new AtomicBoolean(true));
                int id = devTradeID.incrementAndGet();
                double offerPrice = r(Math.max(price, askMap.getOrDefault(symbol, price))
                        + r(ENTRY_CUSHION * price));

                offerPrice = roundToMinVariation(symbol, Direction.Short, offerPrice);

                Order o = placeOfferLimitTIF(offerPrice, defaultS, DAY);

                if (checkDeltaImpact(ct, o)) {
                    devOrderMap.put(id, new OrderAugmented(ct, t, o, BREACH_ADDER));
                    placeOrModifyOrderCheck(apDev, ct, o, new PatientDevHandler(id));
                    outputToSymbolFile(symbol, str("********", t.format(f1)), devOutput);
                    outputToSymbolFile(symbol, str(o.orderId(), id, "ADDER SELL:",
                            devOrderMap.get(id), "yOpen:" + yOpen, "mOpen:" + mOpen, "numCross", numCrosses,
                            "prevClose", prevClose, "p/b/a", price, getBid(symbol), getAsk(symbol),
                            "MAX Entry Dev:", getMaxEntryDev(LocalDate.now(), MAX_ENTRY_DEV),
                            "devFromMinOpen", r10000(price / Math.min(mOpen, yOpen) - 1)), devOutput);
                }
            }
        }
    }

    private static double computeStockOffset(double price, double percent) {
        return Math.max(0.1, Math.round(price * percent * 10d) / 10d);
    }

    private static void overnightHedger(Contract ct, double price, LocalDateTime t, double yOpen, double mOpen) {
        String symbol = ibContractToSymbol(ct);
        double pos = symbolPosMap.get(symbol);
        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();
        boolean added = addedMap.containsKey(symbol) && addedMap.get(symbol).get();

        assert symbol.equalsIgnoreCase(HEDGER_INDEX);

        if (!liquidated && ((NYOpen(t) && pos != 0) || (pos > 0 && (price < mOpen || price < yOpen))
                || (pos < 0 && (price > mOpen || price > yOpen)))) {
            liquidatedMap.put(symbol, new AtomicBoolean(true));
            int id = devTradeID.incrementAndGet();
            Order o = new Order();

            if (pos > 0) {
                o = placeOfferLimitTIF(askMap.getOrDefault(symbol, price), pos, IOC);
            } else if (pos < 0) {
                o = placeBidLimitTIF(bidMap.getOrDefault(symbol, price), Math.abs(pos), IOC);
            }

            devOrderMap.put(id, new OrderAugmented(ct, t, o, BREACH_CUTTER));
            placeOrModifyOrderCheck(apDev, ct, o, new GuaranteeDevHandler(id, apDev));
            outputToSymbolFile(symbol, str("********", t), devOutput);
            outputToSymbolFile(symbol, str(o.orderId(), id, "hedger removal" + (pos > 0 ? "sell" : "buy"),
                    devOrderMap.get(id), "pos", pos, "mOpen:" + mOpen, "price", price), devOutput);
        }

//        pr("ny overnight , added ", NYOvernight(t), added, totalDelta > HEDGE_THRESHOLD, price < mOpen,
//                price < yOpen);

        if (NYOvernight(t) && !added) {
            if (totalDelta > HEDGE_THRESHOLD && price < mOpen && price < yOpen) {
                addedMap.put(symbol, new AtomicBoolean(true));

                int id = devTradeID.incrementAndGet();
                double offerPrice = Math.max(price, askMap.getOrDefault(symbol, price));
                double size =
                        Math.min(10, Math.floor(((totalDelta - HEDGE_THRESHOLD) / (multi.get(HEDGER_INDEX) * price))));

                Order o = placeOfferLimitTIF(offerPrice, size, DAY);
                devOrderMap.put(id, new OrderAugmented(ct, t, o, BREACH_ADDER));
                placeOrModifyOrderCheck(apDev, ct, o, new PatientDevHandler(id));

                outputToSymbolFile(symbol, str("********", t.format(f1)), devOutput);
                outputToSymbolFile(symbol, str(o.orderId(), id, "Hedger SELL:",
                        devOrderMap.get(id), "mOpen:" + mOpen,
                        "p/b/a", price, getBid(symbol), getAsk(symbol), "devFromMonthOpen",
                        r10000(price / mOpen - 1)), devOutput);

            } else if (totalDelta < -HEDGE_THRESHOLD && price > mOpen && price > yOpen) {
                addedMap.put(symbol, new AtomicBoolean(true));

                int id = devTradeID.incrementAndGet();
                double bidPrice = Math.min(price, bidMap.getOrDefault(symbol, price));
                double size =
                        Math.min(10, Math.floor(((-totalDelta - HEDGE_THRESHOLD) / (multi.get(HEDGER_INDEX) * price))));
                Order o = placeBidLimitTIF(bidPrice, size, DAY);
                devOrderMap.put(id, new OrderAugmented(ct, t, o, BREACH_ADDER));
                placeOrModifyOrderCheck(apDev, ct, o, new PatientDevHandler(id));
                outputToSymbolFile(symbol, str("********", t.format(f1)), devOutput);
                outputToSymbolFile(symbol, str(o.orderId(), id, "Hedger BUY:",
                        devOrderMap.get(id), "mOpen:" + mOpen,
                        "p/b/a", price, getBid(symbol), getAsk(symbol), "devFromMonthOpen",
                        r10000(price / mOpen - 1))
                        , devOutput);
            }
        }
    }


    private static boolean usStockOpen(Contract ct, LocalDateTime nyTime) {
        if (ct.currency().equalsIgnoreCase("USD") && ct.secType() == Types.SecType.STK) {
            //ZonedDateTime chinaZdt = ZonedDateTime.of(nyTime, chinaZone);
            //ZonedDateTime usZdt = ZonedDateTime.of(nyTime, nyZone);
            //LocalTime usLt = usZdt.toLocalDateTime().toLocalTime();

            return ltBtwn(nyTime.toLocalTime(), 9, 30, 0, 16, 0, 0);
        } else if (ct.currency().equalsIgnoreCase("HKD") && ct.secType() == Types.SecType.STK) {
            return ltBtwn(nyTime.toLocalTime(), 9, 30, 0, 16, 0, 0);
        }
        return true;
    }

    private static boolean NYOpen(LocalDateTime nyTime) {
        //ZonedDateTime chinaZdt = ZonedDateTime.of(chinaTime, chinaZone);
        //ZonedDateTime usZdt = ZonedDateTime.of(nyTime, nyZone);
        //LocalTime usLt = usZdt.toLocalDateTime().toLocalTime();
        return ltBtwn(nyTime.toLocalTime(), 9, 30, 0, 16, 0, 0);
    }

    private static boolean NYOvernight(LocalDateTime nyTime) {
//        ZonedDateTime chinaZdt = ZonedDateTime.of(chinaTime, chinaZone);
//        ZonedDateTime usZdt = chinaZdt.withZoneSameInstant(nyZone);
//        LocalTime usLt = usZdt.toLocalDateTime().toLocalTime();
        return !ltBtwn(nyTime.toLocalTime(), 9, 30, 0, 16, 0, 0);
    }


    private static void breachCutter(Contract ct, double price, LocalDateTime t, double yOpen, double mOpen) {
        String symbol = ibContractToSymbol(ct);
        double pos = symbolPosMap.get(symbol);
        boolean added = addedMap.containsKey(symbol) && addedMap.get(symbol).get();
        boolean liquidated = liquidatedMap.containsKey(symbol) && liquidatedMap.get(symbol).get();

        if (!liquidated && pos != 0.0) {
            if (pos < 0.0 && (price > mOpen || price > yOpen)) {
                checkIfAdderPending(symbol);
                liquidatedMap.put(symbol, new AtomicBoolean(true));
                int id = devTradeID.incrementAndGet();
                double bidPrice = r(Math.min(price, bidMap.getOrDefault(symbol, price))
                        - r(ENTRY_CUSHION * price));

                bidPrice = roundToMinVariation(symbol, Direction.Long, bidPrice);

                Order o = placeBidLimitTIF(bidPrice, Math.abs(pos), IOC);

                devOrderMap.put(id, new OrderAugmented(ct, t, o, BREACH_CUTTER));
                placeOrModifyOrderCheck(apDev, ct, o, new GuaranteeDevHandler(id, apDev));
                outputToSymbolFile(symbol, str("********", t), devOutput);
                outputToSymbolFile(symbol, str(o.orderId(), id, "Cutter BUY:",
                        "added?" + added, devOrderMap.get(id), "pos", pos, "yOpen:" + yOpen, "mOpen:" + mOpen,
                        "price", price), devOutput);

            } else if (pos > 0.0 && (price < mOpen || price < yOpen)) {
                checkIfAdderPending(symbol);
                liquidatedMap.put(symbol, new AtomicBoolean(true));
                int id = devTradeID.incrementAndGet();

                double offerPrice = r(Math.max(price, askMap.getOrDefault(symbol, price))
                        + r(ENTRY_CUSHION * price));

                offerPrice = roundToMinVariation(symbol, Direction.Short, offerPrice);

                Order o = placeOfferLimitTIF(offerPrice, pos, IOC);

                devOrderMap.put(id, new OrderAugmented(ct, t, o, BREACH_CUTTER));

                placeOrModifyOrderCheck(apDev, ct, o, new GuaranteeDevHandler(id, apDev));

                outputToSymbolFile(symbol, str("********", t), devOutput);
                outputToSymbolFile(symbol, str(o.orderId(), id, "Cutter SELL:",
                        "added?" + added, devOrderMap.get(id), "pos", pos, "yOpen:" + yOpen, "mOpen:" + mOpen,
                        "price", price), devOutput);
            }
        }
    }


    private static double roundToMinVariation(String ticker, Direction dir, double price) {
        if (ticker.equalsIgnoreCase("SGXA50")) {
            return XuTraderHelper.roundToPricePassiveGen(price, dir, 2.5);
        } else if (ticker.equalsIgnoreCase("GXBT")) {
            return XuTraderHelper.roundToPricePassiveGen(price, dir, 5);
        }
        return price;
    }

    private static void checkIfAdderPending(String symbol) {
        devOrderMap.entrySet().stream().filter(e -> e.getValue().getSymbol().equalsIgnoreCase(symbol))
                .filter(e -> e.getValue().getOrderType() == BREACH_ADDER)
                .filter(e -> e.getValue().getAugmentedOrderStatus() == OrderStatus.Submitted)
                .forEach(e -> {
                    outputToSymbolFile(symbol, str("Cancel submitted before cutting"
                            , e.getValue()), devOutput);
                    apDev.cancelOrder(e.getValue().getOrder().orderId());
                });
    }

    private static boolean checkDeltaImpact(Contract ct, Order o) {
        double totalQ = o.totalQuantity();
        double lmtPrice = o.lmtPrice();
        double xxxUSD = fx.getOrDefault(Currency.get(ct.currency()), 1.0);
        String symbol = ibContractToSymbol(ct);

        double impact = getDelta(ct, lmtPrice, totalQ, xxxUSD);

        if (Math.abs(impact) > MAX_DELTA_PER_TRADE) {
            TradingUtility.outputToError(str("IMPACT TOO BIG", impact, ct.symbol(), o.action(),
                    o.lmtPrice(), o.totalQuantity()));
            outputToSymbolFile(symbol, str("IMPACT TOO BIG ", impact), devOutput);
            return false;
        } else {
            outputToSymbolFile(symbol, str("delta impact check PASSED ", Math.round(impact)), devOutput);
            return true;
        }
    }


    @Override
    public void handlePrice(TickType tt, Contract ct, double price, LocalDateTime t) {
        String symbol = ibContractToSymbol(ct);

        //ZonedDateTime chinaZdt = ZonedDateTime.of(t, chinaZone);
        //ZonedDateTime usZdt = chinaZdt.withZoneSameInstant(nyZone);

        LocalDate prevMonthCutoff = getPrevMonthCutoff(ct, getMonthBeginMinus1Day(t.toLocalDate()));
        LocalDateTime dayStartTime = LocalDateTime.of(t.toLocalDate(), ltof(9, 30, 0));

        switch (tt) {
            case LAST:
                liveData.get(symbol).put(t, price);
                lastMap.put(symbol, price);

                if (liveData.get(symbol).size() > 1 && ytdDayData.get(symbol).size() > 1) {

                    double yStart;
                    double mStart;
                    double dStart;

                    if (ytdDayData.get(symbol).firstKey().isAfter(LAST_YEAR_DAY)) {
                        yStart = ytdDayData.get(symbol).ceilingEntry(LAST_YEAR_DAY).getValue().getOpen();
                    } else {
                        yStart = ytdDayData.get(symbol).floorEntry(LAST_YEAR_DAY).getValue().getClose();
                    }

                    if (ytdDayData.get(symbol).firstKey().isAfter(prevMonthCutoff)) {
                        mStart = ytdDayData.get(symbol).ceilingEntry(prevMonthCutoff).getValue().getOpen();
                    } else {
                        mStart = ytdDayData.get(symbol).floorEntry(prevMonthCutoff).getValue().getClose();
                    }

                    if (liveData.get(symbol).firstKey().isAfter(dayStartTime)) {
                        dStart = liveData.get(symbol).ceilingEntry(dayStartTime).getValue();
                    } else {
                        //dStart = liveData.get(symbol).firstEntry().getValue();
                        dStart = liveData.get(symbol).floorEntry(dayStartTime).getValue();
                    }


                    if (symbol.equalsIgnoreCase("XINA50")) {
                        pr("xina50", yStart);
                    }

                    if (symbol.equalsIgnoreCase(HEDGER_INDEX)) {
                        pr(HEDGER_INDEX, price, t, "ystart", yStart,
                                Math.round(10000d * (price / yStart - 1)) / 100d + "%",
                                "mstart", mStart, Math.round(10000d * (price / mStart - 1)) / 100d + "%",
                                "dStart", dStart, Math.round(10000d * (price / dStart - 1)) / 100d + "%",
                                "pos", symbolPosMap.getOrDefault(HEDGER_INDEX, 0.0));
                        //overnightHedger(ct, price, t, yStart, mStart);
                        INDEX_BULL_YEAR.set(price > yStart);
                        INDEX_BULL_MONTH.set(price > mStart);
                        INDEX_BULL_DAY.set(price > dStart);
                        TRIPLE_BULL.set(price > yStart && price > mStart && price > dStart);
                        TRIPLE_BEAR.set(price < yStart && price < mStart && price < dStart);


                    } else {
                        if ((usStockOpen(ct, t) && ct.secType() == Types.SecType.STK) ||
                                ct.secType() == Types.SecType.FUT) {
                            breachCutter(ct, price, t, yStart, mStart);
                            breachAdder(ct, price, t, yStart, mStart, dStart);
                        }
                    }
                }

                if (ytdDayData.get(symbol).containsKey(t.toLocalDate())) {
                    ytdDayData.get(symbol).get(t.toLocalDate()).add(price);
                } else {
                    ytdDayData.get(symbol).put(t.toLocalDate(), new SimpleBar(price));
                }

                break;
            case BID:
                bidMap.put(symbol, price);
                break;
            case ASK:
                askMap.put(symbol, price);
                break;
        }

    }

    private static double getLastPriceFromYtd(Contract ct) {
        String symbol = ibContractToSymbol(ct);
        if (ytdDayData.containsKey(symbol) && ytdDayData.get(symbol).size() > 0) {
            return ytdDayData.get(symbol).lastEntry().getValue().getClose();
        }
        return 0.0;
    }

    @Override
    public void handleVol(TickType tt, String symbol, double vol, LocalDateTime t) {

    }

    @Override
    public void handleGeneric(TickType tt, String symbol, double value, LocalDateTime t) {

    }


    public static void main(String[] args) {
        BreachTrader trader = new BreachTrader();
        trader.connectAndReqPos();
        apDev.cancelAllOrders();

        es.scheduleAtFixedRate(() -> {
            totalDelta = contractPosMap.entrySet().stream()
                    .filter(e -> e.getValue() != 0.0)
                    .mapToDouble(e -> getDelta(e.getKey()
                            , getLastPriceFromYtd(e.getKey()), e.getValue(),
                            fx.getOrDefault(Currency.get(e.getKey().currency()), 1.0))).sum();
            totalAbsDelta = contractPosMap.entrySet().stream()
                    .filter(e -> e.getValue() != 0.0)
                    .mapToDouble(e -> Math.abs(getDelta(e.getKey(), getLastPriceFromYtd(e.getKey()), e.getValue(),
                            fx.getOrDefault(Currency.get(e.getKey().currency()), 1.0)))).sum();
            longDelta = contractPosMap.entrySet().stream()
                    .filter(e -> e.getValue() > 0.0)
                    .mapToDouble(e -> getDelta(e.getKey(), getLastPriceFromYtd(e.getKey()), e.getValue(),
                            fx.getOrDefault(Currency.get(e.getKey().currency()), 1.0))).sum();

            shortDelta = contractPosMap.entrySet().stream()
                    .filter(e -> e.getValue() < 0.0)
                    .mapToDouble(e -> getDelta(e.getKey(), getLastPriceFromYtd(e.getKey()), e.getValue(),
                            fx.getOrDefault(Currency.get(e.getKey().currency()), 1.0))).sum();

            pr(LocalDateTime.now().format(f),
                    "||net delta:" + Math.round(totalDelta / 1000d) + "k",
                    "||abs delta:" + Math.round(totalAbsDelta / 1000d) + "k",
                    "||long/short:", Math.round(longDelta / 1000d) + "k",
                    Math.round(shortDelta / 1000d) + "k");
        }, 0, 20, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            pr("closing hook ");
            outputToFile(str("Ending Trader ", LocalDateTime.now()), startEndTime);
            devOrderMap.forEach((k, v) -> {
                if (v.getAugmentedOrderStatus() != OrderStatus.Filled &&
                        v.getAugmentedOrderStatus() != OrderStatus.PendingCancel) {
                    outputToSymbolFile(v.getSymbol(), str("Shutdown status",
                            LocalDateTime.now().format(f1), v.getAugmentedOrderStatus(), v), devOutput);
                }
            });
            apDev.cancelAllOrders();
        }));
    }
}

