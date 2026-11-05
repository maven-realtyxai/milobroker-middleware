package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.milobroker.concierge.json.Jsons;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WhatsAppSendMediaTest {
  @Test void sendsImageLinkAndCaptionWithBearerAuthentication() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://graph.facebook.com/v21.0/phone/messages"))
      .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer token"))
      .andExpect(content().json("""
        {"messaging_product":"whatsapp","recipient_type":"individual","to":"u1","type":"image",
         "image":{"link":"https://s3/photo?X-Amz-Signature=abc&expires=123","caption":"Home 1 - Rs.25000"}}
        """))
      .andRespond(withSuccess("{\"messages\":[{\"id\":\"wamid.1\"}]}", MediaType.APPLICATION_JSON));
    var client = new WhatsAppClient(builder, "https://graph.facebook.com/v21.0", "token", "phone", "", "", "");
    assertTrue(client.sendMedia("u1", "image", "https://s3/photo?X-Amz-Signature=abc&expires=123", "Home 1 - Rs.25000"));
    server.verify();
  }

  @Test void sendsVideoAndBoundsCaptionWithoutBreakingUnicode() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://graph.facebook.com/v21.0/phone/messages"))
      .andExpect(request -> {
        String body = ((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8);
        var root = Jsons.parseObject(body, "message");
        assertEquals("video", root.getString("type"));
        String caption = root.getJsonObject("video").getString("caption");
        assertEquals(1024, caption.codePointCount(0, caption.length()));
        assertEquals("\uD83C\uDFE0".repeat(1024), caption);
      }).andRespond(withSuccess());
    var client = new WhatsAppClient(builder, "https://graph.facebook.com/v21.0", "token", "phone", "", "", "");
    assertTrue(client.sendMedia("u1", "video", "https://s3/video", "\uD83C\uDFE0".repeat(1100)));
    server.verify();
  }

  @Test void missingCredentialsSkipsAndInvalidMediaIsRejected() {
    var client = new WhatsAppClient(RestClient.builder(), "https://graph.facebook.com/v21.0", "", "", "", "", "");
    assertFalse(client.sendMedia("u1", "image", "https://s3/photo", "Home"));
    assertThrows(IllegalArgumentException.class, () -> client.sendMedia("u1", "document", "https://s3/photo", "Home"));
    assertThrows(IllegalArgumentException.class, () -> client.sendMedia("u1", "image", "http://s3/photo", "Home"));
  }
}
