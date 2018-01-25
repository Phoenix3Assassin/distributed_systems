package app_kvServer;

import logger.LogSetup;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static app_kvServer.IKVServer.CacheStrategy;

public class Cache {

    private static Logger logger = LogManager.getLogger(Cache.class);

    private static int size;
    private static CacheStrategy cacheStrategy;
    private static HashMap<String, String> cache = new HashMap<>();

    private static boolean isCacheSetup = false;

    // variables to be used for strategy eviction
    private static int cacheWeight = 0;
    private static ArrayList<KeyStrategyPair> keyStrategyPairArray = new ArrayList<>();

    private Cache() {
    }

    /**
     * sets-up cache
     *
     * @param sizze    specifies how many key-value pairs the server is allowed
     *                 to keep in-memory
     * @param strategy specifies the cache replacement strategy in case the cache
     *                 is full and there is a GET- or PUT-request on a key that is
     *                 currently not contained in the cache. Options are "FIFO", "LRU",
     *                 and "LFU".
     */
    public static void setup(int sizze, CacheStrategy strategy) {
        logger.info("Initializing cache");
        if (sizze > 0 && !CacheStrategy.None.equals(strategy)) {
            size = sizze;
            cacheStrategy = strategy;
            isCacheSetup = true;
            logger.info("Cache initialized!");
            return;
        }
        logger.warn("Unable to initialize cache. Either size was not greater than 0 or cache strategy was none");
    }

    /**
     * Check if key is in cache.
     * NOTE: does not modify any other properties
     *
     * @return true if key in storage, false otherwise
     */
    public static boolean inCache(String key) {
        return cache.containsKey(key);
    }

    /**
     * Clears the cache
     */
    public static void clearCache() {
        cacheWeight = 0;
        keyStrategyPairArray = new ArrayList<>();
        cache = new HashMap<>();

        logger.info("Cache cleared!");
    }

    /**
     * Looks up value in cache and updates cache using cache strategy if needed - will get value from disk if needed
     * If cache is disabled, it will look up the value from disk
     * If string is empty or null return null
     *
     * @param key key to lookup value in cache or disk
     * @return looked up value if it finds key in cache or disk, if miss in both will return null
     * @throws IOException if unable to read from disk
     */
    public static String lookup(String key) throws IOException {

        // lookup from cache -- in_cache will return false if cache is not setup
        if (inCache(key)) {
            logger.info("Cache hit for key");
            // TODO for LDU and LRU, you should call a function to update keyStrategyPairArray
            return cache.get(key);
        }

        // lookup disk and if cache is setup update it
        String value = Persist.read(key);
        if (isCacheSetup && value != null) {
            updateCache(key, value);
        }

        return value;
    }

    // todo -Abdel maybe- have a cache for write and create a thread to periodically write to disk???

    private static void updateCache(String key, String value) {

        switch (cacheStrategy) {
            case LFU:
                // TODO LFU
                break;
            case LRU:
                // TODO LRU
                break;
            case FIFO:
                if (cache.size() < size) {
                    // just add it since it is less than size
                    cache.put(key, value);
                    keyStrategyPairArray.add(new KeyStrategyPair(key, cacheWeight++));

                } else {
                    cache.remove(keyStrategyPairArray.get(0).getKey());
                    keyStrategyPairArray.remove(0);

                    cache.put(key, value);
                    keyStrategyPairArray.add(new KeyStrategyPair(key, cacheWeight++));
                }
                break;
        }
    }

    private static class KeyStrategyPair implements Comparable<KeyStrategyPair> {
        private String key;
        private int strategyInt;

        private KeyStrategyPair(String key, int strategyInt) {
            this.key = key;
            this.strategyInt = strategyInt;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public int getStrategyInt() {
            return strategyInt;
        }

        public void setStrategyInt(int strategyInt) {
            this.strategyInt = strategyInt;
        }


        /**
         * compares 2 keystrategypairs together
         *
         * @param o object to compare to
         * @return -1 if self is greater than o
         * 1 if self is less than o
         * 0 if equals
         */
        @Override
        public int compareTo(KeyStrategyPair o) {
            if (this.getStrategyInt() < o.getStrategyInt()) {
                return -1;
            } else if (this.getStrategyInt() > o.getStrategyInt()) {
                return 1;
            }
            return 0;
        }
    }

    public static void main(String[] args) throws IOException {

        // testing caching
        new LogSetup("logs/server/server.log", Level.ALL);

        Cache.setup(0, CacheStrategy.FIFO);
        Persist.init();

        Persist.write("ab", "test1");
        Persist.write("ac", "test2");
        Persist.write("ad", "test3");
        Persist.write("ae", "test4");
        Persist.write("af", "test5");
        Persist.write("ag", "test6");
        Persist.write("ah", "test7");
        Persist.write("ai", "test8");
        Persist.write("aj", "test9");
        Persist.write("ak", "test10");

        Cache.lookup("ab");
        System.out.println(Cache.cache.toString());

        Cache.lookup("ab");
        System.out.println(Cache.cache.toString());

        Cache.lookup("ac");
        System.out.println(Cache.cache);

        Cache.lookup("ad");
        System.out.println(Cache.cache);

        Cache.lookup("ae");
        System.out.println(Cache.cache);

        Cache.lookup("af");
        System.out.println(Cache.cache);

        Cache.lookup("ag");
        System.out.println(Cache.cache);

        Cache.lookup("ah");
        System.out.println(Cache.cache);

        Cache.lookup("ai");
        System.out.println(Cache.cache);

        Cache.lookup("aj");
        System.out.println(Cache.cache);

        Cache.lookup("ak");
        System.out.println(Cache.cache);

    }

}
