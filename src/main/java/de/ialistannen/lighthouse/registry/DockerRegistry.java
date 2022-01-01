package de.ialistannen.lighthouse.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.ialistannen.lighthouse.hub.DigestFetchException;
import de.ialistannen.lighthouse.hub.DockerLibraryHelper;
import de.ialistannen.lighthouse.hub.TokenFetchException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DockerRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(DockerRegistry.class);

  private static final Pattern REALM_PATTERN = Pattern.compile("realm=\"(.+?)\"");
  private static final Pattern SERVICE_PATTERN = Pattern.compile("service=\"(.+?)\"");

  private final DockerLibraryHelper libraryHelper;
  private final HttpClient client;
  private final ObjectMapper objectMapper;

  public DockerRegistry(DockerLibraryHelper libraryHelper, HttpClient client) {
    this.libraryHelper = libraryHelper;
    this.client = client;
    this.objectMapper = new ObjectMapper();
  }

  /**
   * Returns the value of the {@code "Authorization"} header to use for communicating with a registry.
   *
   * @param image the image to get it for
   * @return the header
   * @throws URISyntaxException if the image contains invalid chars
   * @throws IOException if an error occurs
   * @throws InterruptedException ?
   * @throws TokenFetchException if fetching failed
   */
  public String getAuthHeader(String image) throws URISyntaxException, IOException, InterruptedException {
    URI authUrl = getAuthUrl(image);
    LOGGER.debug("Build auth URL '{}' for '{}'", authUrl, image);

    return getBearerHeader(authUrl);
  }

  /**
   * Fetches the image digest for a given image and tag. The digest is taken from the received HEADER, as that does not
   * seem to count against the API request limit.
   * <p>
   * The manifest digest is NOT the image ID, but can be found in the local image manifest as "{@code RepoDigests}".
   *
   * @param image the image to get the digest for
   * @param tag the tag to get the digest for
   * @return the digest of the manifest
   * @throws IOException if an error happens
   * @throws InterruptedException ?
   * @throws URISyntaxException if you introduce invalid characters
   * @throws DigestFetchException if the server denied the request
   */
  public String getDigest(String image, String tag)
    throws IOException, InterruptedException, URISyntaxException {
    LOGGER.debug("Fetching digest for '{}':'{}'", image, tag);

    String imageName = libraryHelper.getImageNameWithoutRegistry(image);
    String url = getRegistryUrl(image) + "/v2/%s/manifests/%s".formatted(imageName, tag);

    HttpRequest request = HttpRequest.newBuilder(new URI(url))
      .header("Accept", "application/vnd.docker.distribution.manifest.list.v2+json")
      .header("Accept", "application/vnd.docker.distribution.manifest.v1+json")
      .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
      .header("Authorization", getAuthHeader(image))
      .method("HEAD", BodyPublishers.noBody())
      .build();

    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      LOGGER.info(
        "Failed to fetch image digest tag for '{}':'{}' ({}): {}",
        image,
        tag,
        response.statusCode(),
        response.body()
      );
      throw new DigestFetchException(response.statusCode());
    }
    return response.headers().firstValue("docker-content-digest").orElseThrow();
  }

  private String getBearerHeader(URI authUrl) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(authUrl)
      .GET()
      .build();
    String body = client.send(request, BodyHandlers.ofString()).body();
    String token = objectMapper.readValue(body, ObjectNode.class).get("token").asText();

    LOGGER.debug("Received json response: '{}'", body);

    return "Bearer " + token;
  }

  private URI getAuthUrl(String image)
    throws URISyntaxException, IOException, InterruptedException {
    HttpRequest challengeRequest = HttpRequest.newBuilder(getChallengeUrl(image))
      .header("User-Agent", "Lighthouse")
      .GET()
      // TODO: Include basic auth if present
      .build();

    HttpResponse<Void> challengeResponse = client.send(challengeRequest, BodyHandlers.discarding());
    String header = challengeResponse.headers()
      .firstValue("www-authenticate")
      .orElseThrow(() -> new TokenFetchException("Could not find www-authenticate header"));

    LOGGER.debug("Received header: '{}'", header);

    if (!header.toLowerCase(Locale.ROOT).contains("bearer")) {
      // TODO: Basic
      throw new TokenFetchException("Unknown challenge type: '" + header + "'");
    }

    String realm = getFromAuthenticateHeader(header, REALM_PATTERN);
    String service = getFromAuthenticateHeader(header, SERVICE_PATTERN);
    String scope = "repository:" + libraryHelper.getScopeForImage(image) + ":pull";

    return new URI(realm + "?service=" + service + "&scope=" + scope);
  }

  private String getFromAuthenticateHeader(String input, Pattern regex) {
    Matcher matcher = regex.matcher(input);
    if (!matcher.find()) {
      throw new TokenFetchException("Could not find required part in header");
    }
    return matcher.group(1);
  }

  private URI getChallengeUrl(String image) throws URISyntaxException {
    return new URI(getRegistryUrl(image) + "/v2/");
  }

  private String getRegistryUrl(String image) throws URISyntaxException {
    String normalizedName = libraryHelper.normalizeImageName(image);
    URI nameUri = new URI("https://" + normalizedName);
    String url = "https://" + nameUri.getHost();

    if (nameUri.getPort() > 0) {
      url += ":" + nameUri.getPort();
    }

    LOGGER.debug("Build registry URL '{}' for '{}'", url, image);

    return url;
  }
}