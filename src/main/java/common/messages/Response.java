package common.messages;

public class Response implements KVMessage {

    private long id;

    private String key;
    private String value;
    private StatusType statusType;

    public Response(long id, String key, String value, StatusType statusType) {
        this.key = key;
        this.value = value;
        this.statusType = statusType;
    }

    public long getId() {
        return id;
    }

    @Override
    public String getKey() {
        return key;
    }

    @Override
    public String getValue() {
        return value;
    }

    @Override
    public StatusType getStatus() {
        return statusType;
    }
}