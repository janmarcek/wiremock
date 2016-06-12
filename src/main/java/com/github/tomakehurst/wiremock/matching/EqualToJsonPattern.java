package com.github.tomakehurst.wiremock.matching;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.flipkart.zjsonpatch.JsonDiff;
import com.github.tomakehurst.wiremock.common.Json;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.util.List;

import static com.github.tomakehurst.wiremock.common.Json.deepSize;
import static com.github.tomakehurst.wiremock.common.Json.maxDeepSize;
import static com.google.common.collect.Iterables.getLast;
import static org.apache.commons.lang3.math.NumberUtils.isNumber;

public class EqualToJsonPattern extends StringValuePattern {

    private final JsonNode expected;
    private final Boolean ignoreArrayOrder;
    private final Boolean ignoreExtraElements;

    public EqualToJsonPattern(@JsonProperty("equalToJson") String json,
                              @JsonProperty("ignoreArrayOrder") Boolean ignoreArrayOrder,
                              @JsonProperty("ignoreExtraElements") Boolean ignoreExtraElements) {
        super(json);
        expected = Json.read(json, JsonNode.class);
        this.ignoreArrayOrder = ignoreArrayOrder;
        this.ignoreExtraElements = ignoreExtraElements;
    }

    public String getEqualToJson() {
        return expectedValue;
    }

    private boolean shouldIgnoreArrayOrder() {
        return ignoreArrayOrder != null && ignoreArrayOrder;
    }

    public Boolean isIgnoreArrayOrder() {
        return ignoreArrayOrder;
    }

    private boolean shouldIgnoreExtraElements() {
        return ignoreArrayOrder != null && ignoreExtraElements;
    }

    public Boolean isIgnoreExtraElements() {
        return ignoreExtraElements;
    }

    @Override
    public String getExpected() {
        return Json.prettyPrint(getValue());
    }

    @Override
    public MatchResult match(String value) {
        JsonNode actual = Json.read(value, JsonNode.class);
        ArrayNode diff = (ArrayNode) JsonDiff.asJson(expected, actual);

        double maxNodes = maxDeepSize(expected, actual);
        double distance = diffSize(diff) / maxNodes;

        return MatchResult.partialMatch(distance);
    }

    private int diffSize(ArrayNode diff) {
        int acc = 0;
        for (JsonNode child: diff) {
            String operation = child.findValue("op").textValue();
            JsonNode pathString = getFromPathString(operation, child);
            List<String> path = getPath(pathString.textValue());
            if (!arrayOrderIgnoredAndIsArrayMove(operation, path) && !extraElementsIgnoredAndIsAddition(operation)) {
                JsonNode valueNode = child.findValue("value");
                JsonNode referencedExpectedNode = getNodeAtPath(expected, pathString);
                if (valueNode == null) {
                    acc += deepSize(referencedExpectedNode);
                } else {
                    acc += maxDeepSize(referencedExpectedNode, valueNode);
                }
            }
        }

        return acc;
    }

    private static JsonNode getFromPathString(String operation, JsonNode node) {
        if (operation.equals("move")) {
            return node.findValue("from");
        }

        return node.findValue("path");
    }

    private boolean extraElementsIgnoredAndIsAddition(String operation) {
        return operation.equals("add") && shouldIgnoreExtraElements();
    }

    private boolean arrayOrderIgnoredAndIsArrayMove(String operation, List<String> path) {
        return operation.equals("move") && isNumber(getLast(path)) && shouldIgnoreArrayOrder();
    }

    public static JsonNode getNodeAtPath(JsonNode rootNode, JsonNode path) {
        String pathString = path.toString().equals("\"/\"") ? "\"\"" : path.toString();
        return getNode(rootNode, getPath(pathString), 1);
    }

    private static JsonNode getNode(JsonNode ret, List<String> path, int pos) {
        if (pos >= path.size()) {
            return ret;
        }
        String key = path.get(pos);
        if (ret.isArray()) {
            int keyInt = Integer.parseInt(key.replaceAll("\"", ""));
            return getNode(ret.get(keyInt), path, ++pos);
        } else if (ret.isObject()) {
            if (ret.has(key)) {
                return getNode(ret.get(key), path, ++pos);
            }
            return null;
        } else {
            return ret;
        }
    }

    private static List<String> getPath(String path) {
        List<String> paths = Splitter.on('/').splitToList(path.replaceAll("\"", ""));
        return Lists.newArrayList(Iterables.transform(paths, new DecodePathFunction()));
    }

    private final static class DecodePathFunction implements Function<String, String> {

        @Override
        public String apply(String path) {
            return path.replaceAll("~1", "/").replaceAll("~0", "~"); // see http://tools.ietf.org/html/rfc6901#section-4
        }
    }

}