/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.prometheus;

import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.test.rest.ObjectPath;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xpack.prometheus.proto.RemoteWrite;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Selectors whose {@code __name__} matchers name no single metric, over the layouts metrics are stored in: OTel documents
 * holding several metrics and their attributes, and remote-write documents holding one metric and its labels, including
 * {@code __name__}. Every layout is resolved through the mappings of its own indices, within one query.
 */
public class PrometheusMetricNameSelectorRestIT extends AbstractPrometheusRestIT {

    private static final Instant QUERY_TIME = Instant.parse("2024-05-10T00:00:00Z");
    private static final String OTEL_DATA_STREAM = "metrics-selector.otel-default";

    /**
     * Prometheus reads {@code {__name__!="tx",host="a"}} as every metric of host a but tx. An OTel document holding tx and rx
     * and a remote-write document holding rx both contribute their rx sample, in one query over both indices.
     */
    public void testMixedLayoutsInOneQuery() throws Exception {
        bulkOtel(OTEL_DATA_STREAM, List.of(otelDocument(Map.of("host", "a"), Map.of("tx", 10.0, "rx", 2.0))));
        remoteWrite("rx", "a", 3);

        assertValues("{__name__!=\"tx\",host=\"a\"}", 2, 3);
        assertValues("{__name__=~\"tx|rx\",host=\"a\"}", 10, 2, 3);
        assertValues("{__name__!~\"r.*\",host=\"a\"}", 10);
        assertValues("{host=\"a\"}", 10, 2, 3);
        // the exact name reads the same samples directly
        assertValues("rx{host=\"a\"}", 2, 3);
        assertValues("{__name__!=\"tx\",__name__=\"rx\",host=\"a\"}", 2, 3);
        assertValues("count({__name__=~\"tx|rx\"})", 3);
        // label matchers keep PromQL semantics on both layouts: an absent label matches only the empty string, and repeated
        // matchers are a conjunction
        assertValues("{__name__=~\"tx|rx\",host=\"a\",zone=\"\"}", 10, 2, 3);
        assertValues("{__name__=~\"tx|rx\",host=\"a\",zone!=\"eu\"}", 10, 2, 3);
        assertValues("{__name__=~\"tx|rx\",zone=\"eu\"}");
        assertValues("{__name__=~\"tx|rx\",host=\"a\",host=~\"a|b\"}", 10, 2, 3);
        assertValues("{__name__=~\"tx|rx\",host=\"a\",host=\"b\"}");
        assertValues("sum({__name__=~\"tx|rx\"})", 15);
        assertValues("sum({__name__!=\"tx\",host=\"a\"})", 5);

        Map<String, List<String>> namesByValue = new HashMap<>();
        for (Map<String, Object> series : result(query("{__name__=~\"tx|rx\",host=\"a\"}"))) {
            @SuppressWarnings("unchecked")
            Map<String, String> metric = (Map<String, String>) series.get("metric");
            String value = (String) ((List<?>) series.get("value")).get(1);
            namesByValue.computeIfAbsent(value, v -> new ArrayList<>()).add(metric.get("__name__"));
        }
        assertThat(namesByValue, equalTo(Map.of("10.0", List.of("tx"), "2.0", List.of("rx"), "3.0", List.of("rx"))));
    }

    /**
     * One OTel document holding several metrics yields one series per metric, even though they share every stored label, and
     * a metric missing from a document yields no sample there.
     */
    public void testSeveralMetricsInOneDocument() throws Exception {
        bulkOtel(
            OTEL_DATA_STREAM,
            List.of(
                otelDocument(Map.of("host", "a"), Map.of("tx", 10.0, "rx", 2.0, "drops", 1.0)),
                otelDocument(Map.of("host", "b"), Map.of("tx", 30.0))
            )
        );
        assertValues("{__name__=~\"tx|rx|drops\"}", 10, 2, 1, 30);
        assertValues("{__name__=~\"rx|drops\"}", 2, 1);
        assertValues("max({__name__=~\"tx|rx|drops\"})", 30);
        assertValues("count by (host) ({__name__=~\".+\"})", 3, 1);
    }

    /**
     * A metric is selected under the name an exact metric name resolves to it. An OTel attribute named {@code tx} takes the
     * short name {@code tx} from the metric, so that metric is named {@code metrics.tx} there - by exact names and patterns alike.
     */
    public void testExposedNameFollowsExactNameResolution() throws Exception {
        String collidingStream = "metrics-collide.otel-default";
        bulkOtel(collidingStream, List.of(otelDocument(Map.of("host", "a", "tx", "label"), Map.of("tx", 10.0, "rx", 2.0))));

        assertValues(collidingStream, "{__name__=~\"tx|rx\"}", 2);
        assertValues(collidingStream, "{__name__=~\"metrics\\\\..*\"}", 10);
        assertValues(collidingStream, "{__name__=\"metrics.tx\"}", 10);
        assertValues(collidingStream, "rx", 2);
    }

    /** Only numeric metrics can be read as samples: a pattern selecting a histogram fails rather than silently skipping it. */
    public void testIncompatibleSampleTypesAreRejected() throws Exception {
        String histogramStream = "metrics-histogram.otel-default";
        bulkOtel(
            histogramStream,
            List.of(
                Map.of(
                    "@timestamp",
                    QUERY_TIME.toString(),
                    "attributes",
                    Map.of("host", "a"),
                    "metrics",
                    Map.of("tx", 10.0, "latency", Map.of("values", List.of(0.1, 0.2), "counts", List.of(3, 7)))
                )
            )
        );
        assertValues(histogramStream, "{__name__=~\"t.*\"}", 10);
        ResponseException error = expectThrows(ResponseException.class, () -> query(histogramStream, "{__name__=~\".+\"}", readApiKey));
        assertThat(error.getResponse().getStatusLine().getStatusCode(), equalTo(400));
        assertThat(
            EntityUtils.toString(error.getResponse().getEntity()),
            containsString("metric [latency] of type [histogram] matches the __name__ matchers but only numeric metrics can be selected")
        );
    }

    /** A metric field a user may not read is never selected by name, so its samples are never returned. */
    public void testFieldLevelSecurityHidesMetrics() throws Exception {
        bulkOtel(OTEL_DATA_STREAM, List.of(otelDocument(Map.of("host", "a"), Map.of("tx", 10.0, "rx", 2.0))));
        String restricted = createFieldRestrictedApiKey("metrics.rx");
        List<String> values = new ArrayList<>();
        for (Map<String, Object> series : result(query("metrics-*", "{__name__=~\"tx|rx\",host=\"a\"}", restricted))) {
            values.add((String) ((List<?>) series.get("value")).get(1));
        }
        assertThat(values, containsInAnyOrder("10.0"));
    }

    private static Map<String, Object> otelDocument(Map<String, Object> attributes, Map<String, Object> metrics) {
        return Map.of("@timestamp", QUERY_TIME.toString(), "attributes", attributes, "metrics", metrics);
    }

    /** OTel layout: attributes and metrics as passthrough objects, attributes taking precedence for short names. */
    private void bulkOtel(String dataStream, List<Map<String, Object>> documents) throws IOException {
        Request template = new Request("PUT", "/_index_template/" + dataStream);
        template.setJsonEntity(Strings.format("""
            {
              "index_patterns": ["%s"],
              "data_stream": {},
              "priority": 500,
              "template": {
                "settings": {
                  "index.mode": "time_series",
                  "index.time_series.start_time": "%s"
                },
                "mappings": {
                  "dynamic_templates": [
                    {
                      "histograms": {
                        "path_match": "metrics.latency",
                        "mapping": { "type": "histogram", "time_series_metric": "histogram" }
                      }
                    },
                    {
                      "gauges": {
                        "path_match": "metrics.*",
                        "mapping": { "type": "double", "time_series_metric": "gauge" }
                      }
                    }
                  ],
                  "properties": {
                    "@timestamp": { "type": "date" },
                    "attributes": { "type": "passthrough", "priority": 20, "time_series_dimension": true },
                    "metrics": { "type": "passthrough", "priority": 10 }
                  }
                }
              }
            }
            """, dataStream, QUERY_TIME.minus(1, ChronoUnit.DAYS)));
        client().performRequest(template);
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> document : documents) {
            try (var builder = XContentFactory.jsonBuilder()) {
                body.append("{\"create\":{}}\n").append(Strings.toString(builder.map(document))).append('\n');
            }
        }
        Request bulk = new Request("POST", "/" + dataStream + "/_bulk");
        bulk.addParameter("refresh", "true");
        bulk.setJsonEntity(body.toString());
        ObjectPath response = ObjectPath.createFromResponse(client().performRequest(bulk));
        assertThat(response.toString(), response.evaluate("errors"), equalTo(false));
    }

    /** Remote-write layout: one document per sample, with every label - including {@code __name__} - stored as a label. */
    private void remoteWrite(String metric, String host, double value) throws IOException {
        ingestTestData(
            RemoteWrite.WriteRequest.newBuilder()
                .addTimeseries(
                    RemoteWrite.TimeSeries.newBuilder()
                        .addLabels(label("__name__", metric))
                        .addLabels(label("host", host))
                        .addSamples(sample(value, QUERY_TIME.toEpochMilli()))
                )
                .build(),
            QUERY_TIME
        );
    }

    private String createFieldRestrictedApiKey(String hiddenField) throws IOException {
        Request request = new Request("POST", "/_security/api_key");
        request.setJsonEntity(Strings.format("""
            {
              "name": "prometheus-read-restricted",
              "role_descriptors": {
                "role": {
                  "index": [
                    {
                      "names": ["metrics-*"],
                      "privileges": ["read"],
                      "field_security": { "grant": ["*"], "except": ["%s"] }
                    }
                  ]
                }
              }
            }
            """, hiddenField));
        return ObjectPath.createFromResponse(client().performRequest(request)).evaluate("encoded");
    }

    private void assertValues(String query, double... expected) throws Exception {
        assertValues("metrics-*", query, expected);
    }

    private void assertValues(String index, String query, double... expected) throws Exception {
        List<Double> actual = new ArrayList<>();
        for (Map<String, Object> series : result(query(index, query, readApiKey))) {
            actual.add(Double.parseDouble((String) ((List<?>) series.get("value")).get(1)));
        }
        List<Double> wanted = new ArrayList<>();
        for (double value : expected) {
            wanted.add(value);
        }
        assertThat(query, actual, containsInAnyOrder(wanted.toArray()));
    }

    private ObjectPath query(String query) throws Exception {
        return query("metrics-*", query, readApiKey);
    }

    private ObjectPath query(String index, String query, String apiKey) throws Exception {
        Request request = prometheusReadRequest(
            "/_prometheus/" + index + "/api/v1/query",
            new BasicNameValuePair("query", query),
            new BasicNameValuePair("time", QUERY_TIME.toString())
        );
        request.setOptions(request.getOptions().toBuilder().removeHeader("Authorization").addHeader("Authorization", "ApiKey " + apiKey));
        Response response = client().performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
        ObjectPath path = ObjectPath.createFromResponse(response);
        assertThat(path.evaluate("status"), equalTo("success"));
        return path;
    }

    private static List<Map<String, Object>> result(ObjectPath response) throws IOException {
        return response.evaluate("data.result");
    }
}
