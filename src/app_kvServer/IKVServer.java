package app_kvServer;

public interface IKVServer {
    enum CacheStrategy {
        None("None"),
        LRU("LRU"),
        LFU("LFU"),
        FIFO("FIFO");

        String value;

        CacheStrategy(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }



    /**
     * Get the port number of the server
     * @return  port number
     */
    int getPort();

    /**
     * Get the hostname of the server
     * @return  hostname of server
     */
    String getHostname();

    /**
     * Get the cache strategy of the server
     * @return  cache strategy
     */
    CacheStrategy getCacheStrategy();

    /**
     * Get the cache size
     * @return  cache size
     */
    int getCacheSize();

    /**
     * Check if key is in storage.
     * NOTE: does not modify any other properties
     * @return  true if key in storage, false otherwise
     */
    boolean inStorage(String key);

    /**
     * Check if key is in storage.
     * NOTE: does not modify any other properties
     * @return  true if key in storage, false otherwise
     */
    boolean inCache(String key);

    /**
     * Get the value associated with the key
     * @return  value associated with key
     * @throws Exception
     *      when key not in the key range of the server
     */
    String getKV(String key) throws Exception;

    /**
     * Put the key-value pair into storage
     * @throws Exception
     *      when key not in the key range of the server
     */
    void putKV(String key, String value) throws Exception;

    /**
     * Clear the local cache of the server
     */
    void clearCache();

    /**
     * Clear the storage of the server
     */
    void clearStorage();

    /**
     * Starts running the server
     */
    void run();

    /**
     * Abruptly stop the server without any additional actions
     * NOTE: this includes performing saving to storage
     */
    void kill();

    /**
     * Gracefully stop the server, can perform any additional actions
     */
    void close();

    /**
     * ECS-related start, starts serving requests
     */
    void start();

    /**
     * ECS-related stop, stops serving requests
     */
    void stop();

    /**
     * ECS-related lock, locks the KVServer for write operations
     */
    void lockWrite();

    /**
     * ECS-related unlock, unlocks the KVServer for write operations
     */
    void unlockWrite();

    /**
     * ECS-related moveData, move the given hashRange to the server going by the targetName
     */
    boolean moveData(String[] hashRange, String targetName) throws Exception;
}
