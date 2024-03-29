package app_kvServer;

import logger.LogSetup;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import static app_kvServer.IKVServer.CacheStrategy;

public class Cache {

    private static Logger logger = LogManager.getLogger(Cache.class);

    private static int size;
    private static CacheStrategy cacheStrategy = CacheStrategy.None;
    private static volatile HashMap<String, String> cache = new HashMap<>();

    private static boolean isCacheSetup = false;
    private static int LRU_INIT = Integer.MAX_VALUE;
    private static int LFU_INIT = 0;

    // variables to be used for strategy eviction
    private static volatile ArrayList<KeyStrategyPair> keyStrategyPairArray = new ArrayList<>();

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
            logger.debug("Strategy: " + cacheStrategy.toString());
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
    public static synchronized String lookup(String key) throws IOException {

        // lookup from cache -- in_cache will return false if cache is not setup
        if (inCache(key)) {
            logger.info("Cache hit for key \"" + key + "\"");
            String value = cache.get(key);
            updateCache(key, value);
            return cache.get(key);
        }

        logger.info("Cache miss for key \"" + key + "\".. looking up in database");
        // lookup disk and if cache is setup update it
        String value = Persist.read(key);
        if (isCacheSetup && value != null) {
            updateCache(key, value);
        } else {
            value = Persist.readReplica(key);
            if (isCacheSetup && value != null)
                updateCache(key, value);
        }
        return value;
    }

    protected synchronized static void updateCache(String key, String value) {
        switch (cacheStrategy) {
            case LFU:
                if (inCache(key)){
                    cache.put(key, value);// used incase value in cache need to be modified
                    for (int i = 0; i < keyStrategyPairArray.size(); i++) {
                        KeyStrategyPair pair = keyStrategyPairArray.get(i);
                        if (pair.getKey().equals(key)) {
                            keyStrategyPairArray.set(i, new KeyStrategyPair(pair.getKey(),
                                    pair.getStrategyInt() + 1));
                        }
                    }
                }
                else {
                    if (cache.size() < size) {
                        // just add it since it is less than size
                        cache.put(key, value);
                        keyStrategyPairArray.add(new KeyStrategyPair(key, LFU_INIT + 1));

                    } else {
                        KeyStrategyPair minPair = Collections.min(keyStrategyPairArray);
                        cache.remove(minPair.getKey());
                        keyStrategyPairArray.remove(minPair);

                        cache.put(key, value);
                        keyStrategyPairArray.add(new KeyStrategyPair(key, LFU_INIT + 1));
                    }
                }
                break;
            case LRU:
                if (inCache(key)) {
                    cache.put(key, value); // used incase value in cache need to be modified
                    for (int i = 0; i < keyStrategyPairArray.size(); i++) {
                        KeyStrategyPair pair = keyStrategyPairArray.get(i);
                        if (pair.getKey().equals(key)) {
                            keyStrategyPairArray.set(i, new KeyStrategyPair(pair.getKey(),
                                    LRU_INIT));
                        } else {
                            keyStrategyPairArray.set(i, new KeyStrategyPair(pair.getKey(),
                                    pair.getStrategyInt() - 4));
                        }
                    }
                } else {
                    if (cache.size() < size) {
                        // just add it since it is less than size
                        cache.put(key, value);
                        keyStrategyPairArray.add(new KeyStrategyPair(key, LRU_INIT));

                    } else {
                        KeyStrategyPair minPair = Collections.min(keyStrategyPairArray);
                        cache.remove(minPair.getKey());
                        keyStrategyPairArray.remove(minPair);

                        cache.put(key, value);
                        keyStrategyPairArray.add(new KeyStrategyPair(key, LRU_INIT));
                    }
                }
                break;
            case FIFO:
                if (!inCache(key)) {
                    if (cache.size() < size) {
                        // just add it since it is less than size
                        cache.put(key, value);
                        keyStrategyPairArray.add(new KeyStrategyPair(key, 0));

                    } else {
                        cache.remove(keyStrategyPairArray.get(0).getKey());
                        keyStrategyPairArray.remove(0);

                        cache.put(key, value);
                        keyStrategyPairArray.add(new KeyStrategyPair(key, 0));
                    }
                }else{
                    cache.put(key, value); // used incase value in cache need to be modified
                }
                break;
        }
    }

    protected static synchronized void remove(String key) {
        if (isCacheSetup && inCache(key)) {
            KeyStrategyPair keyStrategyPair = null;
            cache.remove(key);
            for (KeyStrategyPair keyStrPair : keyStrategyPairArray) {
                if (key.equals(keyStrPair.getKey())) {
                    keyStrategyPair = keyStrPair;
                    break;
                }
            }
            if(keyStrategyPair != null){
                keyStrategyPairArray.remove(keyStrategyPair);
            }
        }
    }

    private static class KeyStrategyPair implements Comparable<KeyStrategyPair> {
        private String key;
        // this value is frequency for LFU, MMM for LRY and is not used for fifo
        private int strategyInt;

        private KeyStrategyPair(String key, int strategyInt) {
            this.key = key;
            this.strategyInt = strategyInt;
        }

        private String getKey() {
            return key;
        }

        private void setKey(String key) {
            this.key = key;
        }

        private int getStrategyInt() {
            return strategyInt;
        }

        private void setStrategyInt(int strategyInt) {
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

        Cache.setup(3, CacheStrategy.LFU);
        Persist.init("");

        Persist.write("ab", "test1");
        System.out.println(Cache.cache.toString());

        Persist.write("ab", null);
        System.out.println(Cache.cache.toString());

        Persist.write("ac", "test2");
        System.out.println(Cache.cache.toString());

        Cache.clearCache();

//        Persist.write("ac", null);
//        System.out.println(Cache.cache.toString());

        Persist.write("ad", "test3");
        System.out.println(Cache.cache.toString());

        Persist.write("ae", "test4");
        System.out.println(Cache.cache.toString());

        Persist.write("ac", "testX");
        System.out.println(Cache.cache.toString());

        Persist.write("af", "test5");
        System.out.println(Cache.cache.toString());

        Persist.write("ag", "test6");
        System.out.println(Cache.cache.toString());

        Persist.write("ah", "test7");
        System.out.println(Cache.cache.toString());

        Persist.write("ai", "test8");
        System.out.println(Cache.cache.toString());

        Persist.write("aj", "test9");
        System.out.println(Cache.cache.toString());

        Persist.write("ak", "test10");
        System.out.println(Cache.cache.toString());

        lookup("ab");
        System.out.println(Cache.cache.toString());

        lookup("ab");
        System.out.println(Cache.cache.toString());

        lookup("ab");
        System.out.println(Cache.cache.toString());


    }

}
