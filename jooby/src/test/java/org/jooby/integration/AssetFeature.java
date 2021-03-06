package org.jooby.integration;

import static org.junit.Assert.assertEquals;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.jooby.test.ServerFeature;
import org.junit.Test;

public class AssetFeature extends ServerFeature {

  {
    assets("/assets/**");
  }

  @Test
  public void jsAsset() throws Exception {
    HttpResponse response = Request.Get(uri("/assets/file.js").build()).execute().returnResponse();
    assertEquals("application/javascript;charset=utf-8", response.getFirstHeader("Content-Type")
        .getValue().toLowerCase());
    assertEquals(200, response.getStatusLine().getStatusCode());
    String lastModified = response.getFirstHeader("Last-Modified").getValue();

    response = Request.Get(uri("/assets/file.js").build())
        .addHeader("If-Modified-Since", lastModified).execute().returnResponse();
    assertEquals(304, response.getStatusLine().getStatusCode());
    assertEquals(null, response.getEntity());
  }

  @Test
  public void cssAsset() throws Exception {
    HttpResponse response = Request.Get(uri("/assets/file.css").build()).execute().returnResponse();
    assertEquals("text/css;charset=utf-8", response.getFirstHeader("Content-Type")
        .getValue().toLowerCase());
    assertEquals(200, response.getStatusLine().getStatusCode());
    String lastModified = response.getFirstHeader("Last-Modified").getValue();

    response = Request.Get(uri("/assets/file.css").build())
        .addHeader("If-Modified-Since", lastModified).execute().returnResponse();
    assertEquals(304, response.getStatusLine().getStatusCode());
    assertEquals(null, response.getEntity());
  }

  @Test
  public void imageAsset() throws Exception {
    HttpResponse response = Request.Get(uri("/assets/favicon.ico").build()).execute().returnResponse();
    assertEquals("image/x-icon", response.getFirstHeader("Content-Type")
        .getValue().toLowerCase());
    assertEquals(200, response.getStatusLine().getStatusCode());
    String lastModified = response.getFirstHeader("Last-Modified").getValue();

    response = Request.Get(uri("/assets/favicon.ico").build())
        .addHeader("If-Modified-Since", lastModified).execute().returnResponse();
    assertEquals(304, response.getStatusLine().getStatusCode());
    assertEquals(null, response.getEntity());
  }

}
