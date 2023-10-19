package org.opentripplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.io.IOException;
import java.net.URI;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.opentripplanner.client.model.Pattern;
import org.opentripplanner.client.model.Route;
import org.opentripplanner.client.model.TripPlan;
import org.opentripplanner.client.model.VehicleRentalStation;
import org.opentripplanner.client.parameters.TripPlanParameters;
import org.opentripplanner.client.query.GraphQLQueries;
import org.opentripplanner.client.serialization.ObjectMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OtpApiClient {

  private static final Logger LOG = LoggerFactory.getLogger(OtpApiClient.class);
  private static final String DEFAULT_GRAPHQL_PATH = "/otp/routers/default/index/graphql";

  private final CloseableHttpClient httpClient = HttpClientBuilder.create().build();

  private final URI graphQlUri;
  private final ObjectMapper mapper;

  public OtpApiClient(ZoneId timezone, String baseUrl) {
    this.mapper = ObjectMappers.withTimezone(timezone);
    this.graphQlUri = URI.create(baseUrl + DEFAULT_GRAPHQL_PATH);
  }

  public TripPlan plan(TripPlanParameters req) throws IOException {

    var planQuery = GraphQLQueries.plan();
    var formattedModes =
        req.modes().stream()
            .map(m -> "{mode: %s, qualifier: %s}".formatted(m.mode, m.qualifier))
            .collect(Collectors.joining(", "));
    var formattedQuery =
        planQuery.formatted(
            req.from().lat(),
            req.from().lon(),
            req.to().lat(),
            req.to().lon(),
            formattedModes,
            req.time().toLocalDate().toString(),
            req.time().toLocalTime().toString(),
            req.searchDirection().isArriveBy(),
            req.walkReluctance());

    final var jsonNode = sendRequest(formattedQuery);
    try {
      var plan = jsonNode.at("/data/plan");
      return mapper.treeToValue(plan, TripPlan.class);
    } catch (IOException e) {
      LOG.error("Could not deserialize response: {}", jsonNode.toPrettyString());
      throw e;
    }
  }

  public List<Route> routes() throws IOException {
    var json = sendRequest(GraphQLQueries.routes());
    var type = listType(Route.class);
    return mapper.treeToValue(json.at("/data/routes"), type);
  }

  public List<VehicleRentalStation> vehicleRentalStations()
      throws IOException, InterruptedException {
    var json = sendRequest(GraphQLQueries.vehicleRentalStations());
    var type = listType(VehicleRentalStation.class);
    return mapper.treeToValue(json.at("/data/vehicleRentalStations"), type);
  }

  public List<Pattern> patterns() throws IOException {
    var json = sendRequest(GraphQLQueries.patterns());
    var type = listType(Pattern.class);
    return mapper.treeToValue(json.at("/data/patterns"), type);
  }

  private static CollectionType listType(Class<?> clazz) {
    return TypeFactory.defaultInstance().constructCollectionType(List.class, clazz);
  }

  private JsonNode sendRequest(String formattedQuery) throws IOException {
    LOG.debug("Sending GraphQL query to {}: {}", graphQlUri, formattedQuery);

    var body = mapper.createObjectNode();
    body.put("query", formattedQuery);

    var bodyString = mapper.writeValueAsString(body);

    HttpPost httpPost = new HttpPost(graphQlUri);
    var stringEntity = new StringEntity(bodyString, ContentType.APPLICATION_JSON);
    httpPost.setEntity(stringEntity);
    var response = httpClient.execute(httpPost);
    if (response.getCode() != 200) {
      throw new IOException(
          "HTTP request to '%s' returned status code %s".formatted(graphQlUri, response.getCode()));
    }
    var jsonNode = mapper.readTree(response.getEntity().getContent());

    LOG.debug("Received the following JSON: {}", jsonNode.toPrettyString());
    return jsonNode;
  }
}
