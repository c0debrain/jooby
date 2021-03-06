/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jooby.internal;

import static java.util.Objects.requireNonNull;
import io.undertow.server.HttpServerExchange;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jooby.Body;
import org.jooby.Err;
import org.jooby.MediaType;
import org.jooby.Request;
import org.jooby.Response;
import org.jooby.Route;
import org.jooby.Status;
import org.jooby.Verb;
import org.jooby.internal.undertow.UndertowRequest;
import org.jooby.internal.undertow.UndertowResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.inject.Injector;

@Singleton
public class RouteHandler {

  private static class Holder<T> {
    private T ref;

    private BiFunction<Injector, Route, T> fn;

    public Holder(final BiFunction<Injector, Route, T> fn) {
      this.fn = fn;
    }

    public T get(final Injector injector, final Route route) {
      if (ref == null) {
        ref = fn.apply(injector, route);
      }
      return ref;
    }
  }

  private static final String NO_CACHE = "must-revalidate,no-cache,no-store";

  private static final List<MediaType> ALL = ImmutableList.of(MediaType.all);

  /** The logging system. */
  private final Logger log = LoggerFactory.getLogger(getClass());

  private BodyConverterSelector selector;

  private Set<Route.Definition> routeDefs;

  private Charset charset;

  private Locale locale;

  private Injector rootInjector;

  private Set<Request.Module> modules;

  private Err.Handler err;

  private String applicationPath;

  @Inject
  public RouteHandler(final Injector injector,
      final BodyConverterSelector selector,
      final Set<Request.Module> modules,
      final Set<Route.Definition> routes,
      final @Named("application.path") String path,
      final Charset defaultCharset,
      final Locale defaultLocale,
      final Err.Handler err) {
    this.rootInjector = requireNonNull(injector, "An injector is required.");
    this.selector = requireNonNull(selector, "A message converter selector is required.");
    this.modules = requireNonNull(modules, "Request modules are required.");
    this.routeDefs = requireNonNull(routes, "Routes are required.");
    this.applicationPath = normalizeURI(requireNonNull(path, "An application.path is required."));
    this.charset = requireNonNull(defaultCharset, "A defaultCharset is required.");
    this.locale = requireNonNull(defaultLocale, "A defaultLocale is required.");
    this.err = requireNonNull(err, "An err handler is required.");
  }

  public void handle(final HttpServerExchange exchange, final String method, final String uri,
      final Function<String, String> headers) throws Exception {

    long start = System.currentTimeMillis();
    Verb verb = Verb.valueOf(method.toUpperCase());
    String requestPath = normalizeURI(uri);
    boolean resolveAs404 = false;
    if (applicationPath.equals(requestPath)) {
      requestPath = "/";
    } else if (requestPath.startsWith(applicationPath)) {
      if (!applicationPath.equals("/")) {
        requestPath = requestPath.substring(applicationPath.length());
      }
    } else {
      resolveAs404 = true;
    }

    Map<String, Object> locals = new LinkedHashMap<>();
    // default locals
    locals.put("contextPath", applicationPath);
    locals.put("path", requestPath);

    List<MediaType> accept = Optional.ofNullable(headers.apply("Accept"))
        .map(MediaType::parse)
        .orElse(ALL);

    if (accept.size() > 1) {
      Collections.sort(accept);
    }

    MediaType type = Optional.ofNullable(headers.apply("Content-Type"))
        .map(MediaType::valueOf)
        .orElse(MediaType.all);

    final String path = verb + requestPath;

    log.debug("handling: {}", path);

    log.debug("  content-type: {}", type);

    Charset charset = Optional.ofNullable(type.params().get("charset"))
        .map(Charset::forName)
        .orElse(this.charset);

    Locale locale = Optional.ofNullable(headers.apply("Accept-Language"))
        .map(l -> LocaleUtils.toLocale(l, "-"))
        .orElse(this.locale);

    Holder<Request> req = new Holder<>((injector, route) ->
        new UndertowRequest(exchange, injector, route, locals, selector, type, accept,
            charset, locale));

    Holder<Response> rsp = new Holder<>((injector, route) ->
        new UndertowResponse(exchange, injector, route, locals, selector, charset,
            Optional.ofNullable(headers.apply("Referer"))));

    Injector injector = rootInjector;

    Route notFound = RouteImpl.notFound(verb, path, accept);

    try {

      // bootstrap request modules
      injector = rootInjector.createChildInjector(binder -> {
        binder.bind(Request.class).toProvider(() -> req.ref);
        binder.bind(Response.class).toProvider(() -> rsp.ref);
        for (Request.Module module : modules) {
          module.configure(binder);
        }
      });

      List<Route> routes = resolveAs404
          ? ImmutableList.of(notFound)
          : routes(routeDefs, verb, requestPath, type, accept);

      chain(routes)
          .next(req.get(injector, notFound), rsp.get(injector, notFound));

    } catch (Exception ex) {
      log.debug("execution of: " + path + " resulted in exception", ex);

      Request reqerr = req.get(injector, notFound);
      Response rsperr = rsp.get(injector, notFound);
      ((UndertowResponse) rsperr).reset();

      // execution failed, so find status code
      Status status = statusCode(ex);

      rsperr.header("Cache-Control", NO_CACHE);
      rsperr.status(status);

      try {
        err.handle(reqerr, rsperr, ex);
      } catch (Exception ignored) {
        log.trace("execution of err handler resulted in exceptiion", ignored);
        defaultErrorPage(reqerr, rsperr, err.err(reqerr, rsperr, ex));
      }
    } finally {
      // mark request/response as done.
      Response rspdone = rsp.get(injector, notFound);
      rspdone.end();

      long end = System.currentTimeMillis();
      log.debug("  status -> {} in {}ms", rspdone.status().get(), end - start);

      saveSession(req.get(injector, notFound));
    }
  }

  private void saveSession(final Request req) {
    req.ifSession()
        .ifPresent(session -> req.require(SessionManager.class).requestDone(session));
  }

  private static String normalizeURI(final String uri) {
    return uri.endsWith("/") && uri.length() > 1 ? uri.substring(0, uri.length() - 1) : uri;
  }

  private static Route.Chain chain(final List<Route> routes) {
    return new Route.Chain() {

      private int it = 0;

      @Override
      public void next(final Request req, final Response rsp) throws Exception {
        RouteImpl route = get(routes.get(it++));
        if (rsp.committed()) {
          return;
        }

        // set route
        set(req, route);
        set(rsp, route);

        route.handle(req, rsp, this);
      }

      private RouteImpl get(final Route next) {
        return (RouteImpl) Route.Forwarding.unwrap(next);
      }

      private void set(final Request req, final Route route) {
        UndertowRequest root = (UndertowRequest) Request.Forwarding.unwrap(req);
        root.route(route);
      }

      private void set(final Response rsp, final Route route) {
        UndertowResponse root = (UndertowResponse) Response.Forwarding.unwrap(rsp);
        root.route(route);
      }
    };
  }

  private static List<Route> routes(final Set<Route.Definition> routeDefs, final Verb verb,
      final String path, final MediaType type, final List<MediaType> accept) {
    List<Route> routes = findRoutes(routeDefs, verb, path, type, accept);

    // 406 or 415
    routes.add(RouteImpl.fromStatus((req, rsp, chain) -> {
      if (!rsp.status().isPresent()) {
        Err ex = handle406or415(routeDefs, verb, path, type, accept);
        if (ex != null) {
          throw ex;
        }
      }
      chain.next(req, rsp);
    }, verb, path, Status.NOT_ACCEPTABLE, accept));

    // 405
    routes.add(RouteImpl.fromStatus((req, rsp, chain) -> {
      if (!rsp.status().isPresent()) {
        Err ex = handle405(routeDefs, verb, path, type, accept);
        if (ex != null) {
          throw ex;
        }
      }
      chain.next(req, rsp);
    }, verb, path, Status.METHOD_NOT_ALLOWED, accept));

    // 404
    routes.add(RouteImpl.notFound(verb, path, accept));

    return routes;
  }

  private static List<Route> findRoutes(final Set<Route.Definition> routeDefs, final Verb verb,
      final String path, final MediaType type, final List<MediaType> accept) {

    List<Route> routes = new ArrayList<>();
    for (Route.Definition routeDef : routeDefs) {
      Optional<Route> route = routeDef.matches(verb, path, type, accept);
      if (route.isPresent()) {
        routes.add(route.get());
      }
    }
    return routes;
  }

  private void defaultErrorPage(final Request request, final Response rsp,
      final Map<String, Object> model) throws Exception {
    Status status = rsp.status().get();
    StringBuilder html = new StringBuilder("<!doctype html>")
        .append("<html>\n")
        .append("<head>\n")
        .append("<meta charset=\"").append(request.charset().name()).append("\">\n")
        .append("<style>\n")
        .append("body {font-family: \"open sans\",sans-serif; margin-left: 20px;}\n")
        .append("h1 {font-weight: 300; line-height: 44px; margin: 25px 0 0 0;}\n")
        .append("h2 {font-size: 16px;font-weight: 300; line-height: 44px; margin: 0;}\n")
        .append("footer {font-weight: 300; line-height: 44px; margin-top: 10px;}\n")
        .append("hr {background-color: #f7f7f9;}\n")
        .append("div.trace {border:1px solid #e1e1e8; background-color: #f7f7f9;}\n")
        .append("p {padding-left: 20px;}\n")
        .append("p.tab {padding-left: 40px;}\n")
        .append("</style>\n")
        .append("<title>\n")
        .append(status.value()).append(" ").append(status.reason())
        .append("\n</title>\n")
        .append("<body>\n")
        .append("<h1>").append(status.reason()).append("</h1>\n")
        .append("<hr>");

    model.remove("reason");

    String[] stacktrace = (String[]) model.remove("stacktrace");

    for (Entry<String, Object> entry : model.entrySet()) {
      Object value = entry.getValue();
      if (value != null) {
        html.append("<h2>").append(entry.getKey()).append(": ").append(entry.getValue())
            .append("</h2>\n");
      }
    }

    if (stacktrace != null) {
      html.append("<h2>stack:</h2>\n")
          .append("<div class=\"trace\">\n");

      Arrays.stream(stacktrace).forEach(line -> {
        html.append("<p class=\"line");
        if (line.startsWith("\t")) {
          html.append(" tab");
        }
        html.append("\">")
            .append("<code>")
            .append(line.replace("\t", "  "))
            .append("</code>")
            .append("</p>\n");
      });
      html.append("</div>\n");
    }

    html.append("<footer>powered by Jooby</footer>\n")
        .append("</body>\n")
        .append("</html>\n");

    rsp.header("Cache-Control", NO_CACHE);
    ((UndertowResponse) (Response.Forwarding.unwrap(rsp)))
        .send(Body.body(html), BuiltinBodyConverter.formatAny);
  }

  private Status statusCode(final Exception ex) {
    if (ex instanceof Err) {
      return Status.valueOf(((Err) ex).statusCode());
    }
    if (ex instanceof IllegalArgumentException) {
      return Status.BAD_REQUEST;
    }
    if (ex instanceof NoSuchElementException) {
      return Status.BAD_REQUEST;
    }
    return Status.SERVER_ERROR;
  }

  private static Err handle405(final Set<Route.Definition> routeDefs, final Verb verb,
      final String uri,
      final MediaType type, final List<MediaType> accept) {

    if (alternative(routeDefs, verb, uri).size() > 0) {
      return new Err(Status.METHOD_NOT_ALLOWED, verb + uri);
    }

    return null;
  }

  private static List<Route> alternative(final Set<Route.Definition> routeDefs, final Verb verb,
      final String uri) {
    List<Route> routes = new LinkedList<>();
    Set<Verb> verbs = EnumSet.allOf(Verb.class);
    verbs.remove(verb);
    for (Verb alt : verbs) {
      findRoutes(routeDefs, alt, uri, MediaType.all, ALL)
          .stream()
          // skip glob pattern
          .filter(r -> !r.pattern().contains("*"))
          .forEach(routes::add);

    }
    return routes;
  }

  private static Err handle406or415(final Set<Route.Definition> routeDefs, final Verb verb,
      final String path, final MediaType contentType, final List<MediaType> accept) {
    for (Route.Definition routeDef : routeDefs) {
      Optional<Route> route = routeDef.matches(verb, path, MediaType.all, ALL);
      if (route.isPresent() && !route.get().pattern().contains("*")) {
        if (!routeDef.canProduce(accept)) {
          return new Err(Status.NOT_ACCEPTABLE, accept.stream()
              .map(MediaType::name)
              .collect(Collectors.joining(", ")));
        }
        return new Err(Status.UNSUPPORTED_MEDIA_TYPE, contentType.name());
      }
    }
    return null;
  }

  @Override
  public String toString() {
    StringBuilder buffer = new StringBuilder();
    int verbMax = 0, routeMax = 0, consumesMax = 0, producesMax = 0;
    for (Route.Definition routeDef : routeDefs) {
      verbMax = Math.max(verbMax, routeDef.verb().length());

      routeMax = Math.max(routeMax, routeDef.pattern().length());

      consumesMax = Math.max(consumesMax, routeDef.consumes().toString().length());

      producesMax = Math.max(producesMax, routeDef.produces().toString().length());
    }

    String format = "  %-" + verbMax + "s %-" + routeMax + "s    %" + consumesMax + "s     %"
        + producesMax + "s    (%s)\n";

    for (Route.Definition routeDef : routeDefs) {
      buffer.append(String.format(format, routeDef.verb(), routeDef.pattern(),
          routeDef.consumes(), routeDef.produces(), routeDef.name()));
    }

    return buffer.toString();
  }
}
