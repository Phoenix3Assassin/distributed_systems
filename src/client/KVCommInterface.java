package client;

import common.KVMessage;

public interface KVCommInterface {

	/**
	 * Establishes a connection to the KV Server.
	 *
	 * @throws Exception
	 *             if connection could not be established.
	 */
	void connect() throws Exception;

	/**
	 * disconnects the main.java.client from the currently connected server.
	 */
	void disconnect();

	/**
	 * @return	true if connected to server, false otherwise
	 */
    boolean isConnected();

	/**
	 * Inserts a key-value pair into the KVServer.
	 *
	 * @param key
	 *            the key that identifies the given value.
	 * @param value
	 *            the value that is indexed by the given key.
	 * @return a message that confirms the insertion of the tuple or an error.
	 * @throws Exception
	 *             if put command cannot be executed (e.g. not connected to any
	 *             KV server).
	 */
	KVMessage put(String key, String value) throws Exception;

	/**
	 * Retrieves the value for a given key from the KVServer.
	 *
	 * @param key
	 *            the key that identifies the value.
	 * @return the value, which is indexed by the given key.
	 * @throws Exception
	 *             if put command cannot be executed (e.g. not connected to any
	 *             KV server).
	 */
	KVMessage get(String key) throws Exception;
}
