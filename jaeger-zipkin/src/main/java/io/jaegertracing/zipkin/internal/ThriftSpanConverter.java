/*
 * Copyright (c) 2016, Uber Technologies, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.jaegertracing.zipkin.internal;

import com.google.gson.Gson;
import com.twitter.zipkin.thriftjava.Annotation;
import com.twitter.zipkin.thriftjava.AnnotationType;
import com.twitter.zipkin.thriftjava.BinaryAnnotation;
import com.twitter.zipkin.thriftjava.Endpoint;
import com.twitter.zipkin.thriftjava.zipkincoreConstants;
import io.jaegertracing.internal.Constants;
import io.jaegertracing.internal.JaegerSpan;
import io.jaegertracing.internal.JaegerSpanContext;
import io.jaegertracing.internal.JaegerTracer;
import io.jaegertracing.internal.LogData;
import io.opentracing.tag.Tags;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ThriftSpanConverter {

  private static final Charset UTF_8 = Charset.forName("UTF-8");
  private static final Gson gson = new Gson();

  public static com.twitter.zipkin.thriftjava.Span convertSpan(JaegerSpan jaegerSpan) {
    JaegerTracer tracer = jaegerSpan.getTracer();
    Endpoint host = new Endpoint(tracer.getIpv4(), (short) 0, tracer.getServiceName());

    JaegerSpanContext context = jaegerSpan.context();
    return new com.twitter.zipkin.thriftjava.Span(
            context.getTraceId(),
            jaegerSpan.getOperationName(),
            context.getSpanId(),
            buildAnnotations(jaegerSpan, host),
            buildBinaryAnnotations(jaegerSpan, host))
        .setParent_id(context.getParentId())
        .setDebug(context.isDebug())
        .setTimestamp(jaegerSpan.getStart())
        .setDuration(jaegerSpan.getDuration());
  }

  private static List<Annotation> buildAnnotations(JaegerSpan jaegerSpan, Endpoint host) {
    List<Annotation> annotations = new ArrayList<Annotation>();

    if (ConverterUtil.isRpc(jaegerSpan)) {
      String startLabel = zipkincoreConstants.SERVER_RECV;
      String endLabel = zipkincoreConstants.SERVER_SEND;
      if (ConverterUtil.isRpcClient(jaegerSpan)) {
        startLabel = zipkincoreConstants.CLIENT_SEND;
        endLabel = zipkincoreConstants.CLIENT_RECV;
      }

      annotations.add(new Annotation(jaegerSpan.getStart(), startLabel).setHost(host));
      annotations.add(new Annotation(jaegerSpan.getStart() + jaegerSpan.getDuration(), endLabel).setHost(host));
    }

    List<LogData> logs = jaegerSpan.getLogs();
    if (logs != null) {
      for (LogData logData : logs) {
        String logMessage = logData.getMessage();
        Map<String, ?> logFields = logData.getFields();
        if (logMessage != null) {
          annotations.add(new Annotation(logData.getTime(), logMessage));
        } else if (logFields != null) {
          annotations.add(new Annotation(logData.getTime(), gson.toJson(logFields)));
        }
      }
    }

    return annotations;
  }

  private static List<BinaryAnnotation> buildBinaryAnnotations(JaegerSpan jaegerSpan, Endpoint host) {
    List<BinaryAnnotation> binaryAnnotations = new ArrayList<BinaryAnnotation>();
    Map<String, Object> tags = jaegerSpan.getTags();
    boolean isRpc = ConverterUtil.isRpc(jaegerSpan);
    boolean isClient = ConverterUtil.isRpcClient(jaegerSpan);
    boolean firstSpanInProcess = jaegerSpan.getReferences().isEmpty() || ConverterUtil.isRpcServer(jaegerSpan);

    if (firstSpanInProcess) {
      Map<String, ?> processTags = jaegerSpan.getTracer().tags();
      // add tracer tags to first zipkin jaegerSpan in a process but remove "ip" tag as it is
      // taken care of separately.
      for (Map.Entry<String, ?> entry : processTags.entrySet()) {
        String tagKey = entry.getKey();
        if (!Constants.TRACER_IP_TAG_KEY.equals(tagKey)) {
          Object tagValue = entry.getValue();
          // add a tracer. prefix to process tags for zipkin
          binaryAnnotations.add(buildBinaryAnnotation("tracer." + tagKey, tagValue));
        }
      }
    }

    Endpoint peerEndpoint = extractPeerEndpoint(tags);
    if (peerEndpoint != null && isClient) {
      String key =
          isClient ? zipkincoreConstants.SERVER_ADDR : zipkincoreConstants.CLIENT_ADDR;

      binaryAnnotations.add(
          new BinaryAnnotation()
              .setKey(key)
              .setValue(new byte[] {1})
              .setAnnotation_type(AnnotationType.BOOL)
              .setHost(peerEndpoint));
    }

    if (!isRpc) {
      byte[] componentName;
      Object componentTag = tags.get(Tags.COMPONENT.getKey());
      if (componentTag instanceof String) {
        componentName = componentTag.toString().getBytes(UTF_8);
      } else {
        // spans always have associated tracers, and service names
        componentName = jaegerSpan.getTracer().getServiceName().getBytes(UTF_8);
      }

      binaryAnnotations.add(
          new BinaryAnnotation()
              .setKey(zipkincoreConstants.LOCAL_COMPONENT)
              .setValue(componentName)
              .setAnnotation_type(AnnotationType.STRING)
              .setHost(host));
    }

    if (tags != null) {
      for (Map.Entry<String, Object> entry : tags.entrySet()) {
        String tagKey = entry.getKey();
        // Every value is converted to string because zipkin search doesn't
        // work well with ints, and bytes.
        Object tagValue = entry.getValue();
        binaryAnnotations.add(buildBinaryAnnotation(tagKey, tagValue));
      }
    }
    return binaryAnnotations;
  }

  private static BinaryAnnotation buildBinaryAnnotation(String tagKey, Object tagValue) {
    BinaryAnnotation banno = new BinaryAnnotation().setKey(tagKey);
    banno.setValue(String.valueOf(tagValue).getBytes(UTF_8)).setAnnotation_type(AnnotationType.STRING);
    return banno;
  }

  /**
   * Extract peer Endpoint from tags
   *
   * @param tags tags
   * @return null or peer endpoint
   */
  public static Endpoint extractPeerEndpoint(Map<String, Object> tags) {
    Object peerIpv4 = tags.get(Tags.PEER_HOST_IPV4.getKey());
    Object peerPort = tags.get(Tags.PEER_PORT.getKey());
    Object peerService = tags.get(Tags.PEER_SERVICE.getKey());

    if (peerIpv4 == null && peerPort == null && peerService == null) {
      return null;
    }

    Endpoint peerEndpoint = new Endpoint(0, (short) 0, "");

    if (peerIpv4 instanceof Integer) {
      peerEndpoint.setIpv4((Integer) peerIpv4);
    }
    if (peerPort instanceof Number) {
      peerEndpoint.setPort(((Number)peerPort).shortValue());
    }
    if (peerService instanceof String) {
      peerEndpoint.setService_name((String) peerService);
    }

    return peerEndpoint;
  }
}
