package com.dailysync.omofun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * OmoFun watchhistory.v1 的 protobuf 消息 —— 手写编解码，不跑 protoc。
 *
 * <p>字段号全部逆向自客户端 bundle（与 Rust 版 proto.rs 一一对应）：
 * 这些 message 没有 .proto 源文件，用 protoc 反而要先伪造一份；手写 CodedOutputStream
 * 还有一个关键好处：**签名覆盖的字节就是这里编码出的字节**，签名与发送内容天然一致。
 *
 * <p>只实现实际用到的四个 RPC：ListHistories / GetHistory / RecordProgress /
 * （DeleteHistory 未使用，不实现）。
 */
public final class OmofunProto {

    public static final int STATUS_UNSPECIFIED = 0;
    public static final int STATUS_IN_PROGRESS = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_PAUSED = 3;

    private static final ObjectMapper JSON = new ObjectMapper();

    private OmofunProto() {
    }

    // ---------- 消息模型 ----------

    public record ContentRef(String type, long id, String key) {
    }

    public record HistoryItem(
            long userId,
            ContentRef content,
            String title,
            String lastEpisodeId,
            int lastEpisodeIndex,
            String lastLineId,
            long progressSeconds,
            float progressPercent,
            long totalDurationSeconds,
            int status,
            long version,
            Struct attributes,
            Timestamp updatedAt,
            Struct resourceSnapshot) {

        /** 业务主键：{@code 类型:id}，如 vod:18057；content 缺失时返回 null。 */
        public String contentKey() {
            return content == null ? null : content.type() + ":" + content.id();
        }

        public String updatedAtIso() {
            return OmofunTime.fromTimestamp(updatedAt);
        }
    }

    public record ListHistoriesRequest(
            String contentType,
            int status,
            long cursorId,
            long cursorVersion,
            int limit,
            boolean orderDesc,
            boolean includeResource) {
    }

    public record ListHistoriesResponse(
            java.util.List<HistoryItem> histories,
            long nextCursor,
            long nextVersion) {
    }

    public record ProgressSnapshot(
            String episodeId,
            int episodeIndex,
            String lineId,
            long positionSeconds,
            float positionPercent,
            long totalSeconds,
            Struct metadata,
            boolean markCompleted) {
    }

    public record RecordProgressRequest(
            ContentRef content,
            String title,
            ProgressSnapshot snapshot,
            Struct attributes,
            long clientVersion) {
    }

    public record RecordProgressResponse(HistoryItem history) {
    }

    public record GetHistoryRequest(ContentRef content, boolean includeResource) {
    }

    public record GetHistoryResponse(HistoryItem history) {
    }

    // ---------- 编码 ----------

    public static byte[] encodeListHistoriesRequest(ListHistoriesRequest r) {
        return encode(out -> {
            writeString(out, 1, r.contentType());
            writeInt32(out, 2, r.status());
            writeUInt64(out, 3, r.cursorId());
            writeUInt64(out, 4, r.cursorVersion());
            writeInt32(out, 5, r.limit());
            writeBool(out, 6, r.orderDesc());
            writeBool(out, 7, r.includeResource());
        });
    }

    public static byte[] encodeGetHistoryRequest(GetHistoryRequest r) {
        return encode(out -> {
            writeMessage(out, 1, r.content() == null ? null : encodeContentRef(r.content()));
            writeBool(out, 2, r.includeResource());
        });
    }

    public static byte[] encodeRecordProgressRequest(RecordProgressRequest r) {
        return encode(out -> {
            writeMessage(out, 1, r.content() == null ? null : encodeContentRef(r.content()));
            writeString(out, 2, r.title());
            writeMessage(out, 3, r.snapshot() == null ? null : encodeProgressSnapshot(r.snapshot()));
            writeMessage(out, 4, r.attributes() == null ? null : r.attributes().toByteArray());
            writeInt64(out, 5, r.clientVersion());
        });
    }

    private static byte[] encodeContentRef(ContentRef c) {
        return encode(out -> {
            writeString(out, 1, c.type());
            writeUInt64(out, 2, c.id());
            writeString(out, 3, c.key());
        });
    }

    private static byte[] encodeProgressSnapshot(ProgressSnapshot s) {
        return encode(out -> {
            writeString(out, 1, s.episodeId());
            writeInt32(out, 2, s.episodeIndex());
            writeString(out, 3, s.lineId());
            writeUInt32(out, 4, s.positionSeconds());
            writeFloat(out, 5, s.positionPercent());
            writeUInt32(out, 6, s.totalSeconds());
            writeMessage(out, 7, s.metadata() == null ? null : s.metadata().toByteArray());
            writeBool(out, 8, s.markCompleted());
        });
    }

    // ---------- 解码 ----------

    public static ListHistoriesResponse decodeListHistoriesResponse(byte[] data) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        java.util.List<HistoryItem> histories = new java.util.ArrayList<>();
        long nextCursor = 0;
        long nextVersion = 0;
        while (true) {
            int tag = in.readTag();
            if (tag == 0) {
                break;
            }
            switch (tag) {
                case 10 -> histories.add(decodeHistoryItem(in.readByteArray()));
                case 16 -> nextCursor = in.readUInt64();
                case 24 -> nextVersion = in.readUInt64();
                default -> {
                    if (!in.skipField(tag)) {
                        return new ListHistoriesResponse(histories, nextCursor, nextVersion);
                    }
                }
            }
        }
        return new ListHistoriesResponse(histories, nextCursor, nextVersion);
    }

    public static GetHistoryResponse decodeGetHistoryResponse(byte[] data) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        HistoryItem history = null;
        while (true) {
            int tag = in.readTag();
            if (tag == 0) {
                break;
            }
            if (tag == 10) {
                history = decodeHistoryItem(in.readByteArray());
            } else if (!in.skipField(tag)) {
                break;
            }
        }
        return new GetHistoryResponse(history);
    }

    public static RecordProgressResponse decodeRecordProgressResponse(byte[] data) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        HistoryItem history = null;
        while (true) {
            int tag = in.readTag();
            if (tag == 0) {
                break;
            }
            if (tag == 10) {
                history = decodeHistoryItem(in.readByteArray());
            } else if (!in.skipField(tag)) {
                break;
            }
        }
        return new RecordProgressResponse(history);
    }

    private static HistoryItem decodeHistoryItem(byte[] data) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        long userId = 0;
        ContentRef content = null;
        String title = "";
        String lastEpisodeId = "";
        int lastEpisodeIndex = 0;
        String lastLineId = "";
        long progressSeconds = 0;
        float progressPercent = 0;
        long totalDurationSeconds = 0;
        int status = 0;
        long version = 0;
        Struct attributes = null;
        Timestamp updatedAt = null;
        Struct resourceSnapshot = null;
        while (true) {
            int tag = in.readTag();
            if (tag == 0) {
                break;
            }
            switch (tag) {
                case 8 -> userId = in.readUInt64();
                case 18 -> content = decodeContentRef(in.readByteArray());
                case 26 -> title = in.readString();
                case 34 -> lastEpisodeId = in.readString();
                case 40 -> lastEpisodeIndex = in.readInt32();
                case 50 -> lastLineId = in.readString();
                case 56 -> progressSeconds = Integer.toUnsignedLong(in.readUInt32());
                case 69 -> progressPercent = in.readFloat();
                case 72 -> totalDurationSeconds = Integer.toUnsignedLong(in.readUInt32());
                case 80 -> status = in.readInt32();
                case 88 -> version = in.readUInt64();
                case 98 -> attributes = Struct.parseFrom(in.readByteArray());
                case 106 -> updatedAt = Timestamp.parseFrom(in.readByteArray());
                case 114 -> resourceSnapshot = Struct.parseFrom(in.readByteArray());
                default -> {
                    if (!in.skipField(tag)) {
                        return new HistoryItem(userId, content, title, lastEpisodeId, lastEpisodeIndex,
                                lastLineId, progressSeconds, progressPercent, totalDurationSeconds,
                                status, version, attributes, updatedAt, resourceSnapshot);
                    }
                }
            }
        }
        return new HistoryItem(userId, content, title, lastEpisodeId, lastEpisodeIndex,
                lastLineId, progressSeconds, progressPercent, totalDurationSeconds,
                status, version, attributes, updatedAt, resourceSnapshot);
    }

    private static ContentRef decodeContentRef(byte[] data) throws IOException {
        CodedInputStream in = CodedInputStream.newInstance(data);
        String type = "";
        long id = 0;
        String key = "";
        while (true) {
            int tag = in.readTag();
            if (tag == 0) {
                break;
            }
            switch (tag) {
                case 10 -> type = in.readString();
                case 16 -> id = in.readUInt64();
                case 26 -> key = in.readString();
                default -> {
                    if (!in.skipField(tag)) {
                        return new ContentRef(type, id, key);
                    }
                }
            }
        }
        return new ContentRef(type, id, key);
    }

    // ---------- Struct（attributes / resourceSnapshot）与 JSON ----------

    public static Struct jsonToStruct(JsonNode node) {
        Struct.Builder builder = Struct.newBuilder();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> builder.putFields(e.getKey(), jsonToValue(e.getValue())));
        }
        return builder.build();
    }

    public static ObjectNode structToJson(Struct struct) {
        ObjectNode obj = JSON.createObjectNode();
        if (struct != null) {
            struct.getFieldsMap().forEach((k, v) -> obj.set(k, valueToJson(v)));
        }
        return obj;
    }

    public static String structToJsonString(Struct struct) {
        if (struct == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(structToJson(struct));
        } catch (Exception e) {
            return null;
        }
    }

    public static Struct jsonStringToStruct(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return jsonToStruct(JSON.readTree(raw));
        } catch (Exception e) {
            // 手工改库写坏了就当没有 attributes，不阻塞保活（与旧版 from_str().ok() 一致）
            return null;
        }
    }

    /** 从 attributes / resourceSnapshot 里找封面 URL（旧版 cover_of 的等价实现）。 */
    public static String coverOf(Struct attributes, Struct resourceSnapshot) {
        Struct[] holders = {attributes, resourceSnapshot};
        for (Struct holder : holders) {
            if (holder == null) {
                continue;
            }
            Value value = holder.getFieldsMap().get("cover_url");
            if (value != null && value.hasStringValue()) {
                return value.getStringValue();
            }
        }
        return null;
    }

    private static Value jsonToValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (node.isBoolean()) {
            return Value.newBuilder().setBoolValue(node.booleanValue()).build();
        }
        if (node.isNumber()) {
            return Value.newBuilder().setNumberValue(node.doubleValue()).build();
        }
        if (node.isTextual()) {
            return Value.newBuilder().setStringValue(node.textValue()).build();
        }
        if (node.isArray()) {
            ListValue.Builder list = ListValue.newBuilder();
            for (JsonNode item : node) {
                list.addValues(jsonToValue(item));
            }
            return Value.newBuilder().setListValue(list).build();
        }
        if (node.isObject()) {
            return Value.newBuilder().setStructValue(jsonToStruct(node)).build();
        }
        return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
    }

    private static JsonNode valueToJson(Value value) {
        var nodes = JSON.getNodeFactory();
        return switch (value.getKindCase()) {
            case NULL_VALUE, KIND_NOT_SET -> nodes.nullNode();
            case NUMBER_VALUE -> {
                double n = value.getNumberValue();
                if (!Double.isFinite(n)) {
                    yield nodes.nullNode();
                }
                if (n == Math.rint(n) && Math.abs(n) <= 9_007_199_254_740_992d) {
                    yield nodes.numberNode((long) n);
                }
                yield nodes.numberNode(n);
            }
            case STRING_VALUE -> nodes.textNode(value.getStringValue());
            case BOOL_VALUE -> nodes.booleanNode(value.getBoolValue());
            case STRUCT_VALUE -> structToJson(value.getStructValue());
            case LIST_VALUE -> {
                ArrayNode arr = JSON.createArrayNode();
                value.getListValue().getValuesList().forEach(v -> arr.add(valueToJson(v)));
                yield arr;
            }
        };
    }

    // ---------- 底层写入 ----------

    private interface Writer {
        void write(CodedOutputStream out) throws IOException;
    }

    private static byte[] encode(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream out = CodedOutputStream.newInstance(bytes);
            writer.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            // CodedOutputStream 写内存流不会真正失败；发生即是编程错误
            throw new IllegalStateException("protobuf 编码失败", e);
        }
    }

    private static void writeString(CodedOutputStream out, int field, String value) throws IOException {
        if (value != null && !value.isEmpty()) {
            out.writeString(field, value);
        }
    }

    private static void writeMessage(CodedOutputStream out, int field, byte[] value) throws IOException {
        if (value != null) {
            out.writeByteArray(field, value);
        }
    }

    private static void writeBool(CodedOutputStream out, int field, boolean value) throws IOException {
        if (value) {
            out.writeBool(field, true);
        }
    }

    private static void writeInt32(CodedOutputStream out, int field, int value) throws IOException {
        if (value != 0) {
            out.writeInt32(field, value);
        }
    }

    private static void writeUInt32(CodedOutputStream out, int field, long value) throws IOException {
        if (value != 0) {
            out.writeUInt32(field, (int) value);
        }
    }

    private static void writeUInt64(CodedOutputStream out, int field, long value) throws IOException {
        if (value != 0) {
            out.writeUInt64(field, value);
        }
    }

    private static void writeInt64(CodedOutputStream out, int field, long value) throws IOException {
        if (value != 0) {
            out.writeInt64(field, value);
        }
    }

    private static void writeFloat(CodedOutputStream out, int field, float value) throws IOException {
        if (value != 0.0f) {
            out.writeFloat(field, value);
        }
    }
}
