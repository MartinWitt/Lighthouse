package de.ialistannen.shipit.notifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import de.ialistannen.shipit.docker.ShipItContainerUpdate;
import de.ialistannen.shipit.docker.ShipItImageUpdate;
import de.ialistannen.shipit.hub.ImageInformation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DiscordNotifier implements Notifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(DiscordNotifier.class);

  private final HttpClient httpClient;
  private final URI url;
  private final ObjectMapper objectMapper;

  public DiscordNotifier(HttpClient httpClient, URI url) {
    this.httpClient = httpClient;
    this.url = url;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public void notify(List<ShipItContainerUpdate> updates) {
    if (updates.isEmpty()) {
      return;
    }
    LOGGER.info("Notifying in discord");

    try {
      ObjectNode payload = buildPayload(updates);

      HttpRequest request = HttpRequest.newBuilder(url)
        .POST(BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
        .header("Content-Type", "application/json")
        .build();

      HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
      if (response.statusCode() != 200 && response.statusCode() != 204) {
        LOGGER.warn("Failed to notify (HTTP {}): {}", response.statusCode(), response.body());
      }
    } catch (IOException | InterruptedException e) {
      LOGGER.warn("Failed to notify!", e);
    }
  }

  private ObjectNode buildPayload(List<ShipItContainerUpdate> updates) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.set("username", new TextNode("Ship it!"));
    ArrayNode embeds = objectMapper.createArrayNode();

    updates.stream()
      .map(this::buildEmbed)
      .limit(10)
      .forEach(embeds::add);

    payload.set("embeds", embeds);
    return payload;
  }

  private ObjectNode buildEmbed(ShipItContainerUpdate update) {
    ShipItImageUpdate imageUpdate = update.imageUpdate();
    ImageInformation remoteImageInfo = imageUpdate.remoteImageInfo();

    ObjectNode embed = objectMapper.createObjectNode();
    embed.set("title", new TextNode(remoteImageInfo.imageName() + ":" + remoteImageInfo.tag()));
    embed.set("description", new TextNode(getUpdateText(imageUpdate)));
    embed.set(
      "url",
      new TextNode("https://hub.docker.com/r/%s" .formatted(remoteImageInfo.imageName()))
    );
    embed.set("timestamp", new TextNode(remoteImageInfo.lastUpdated().toString()));
    embed.set("color", new IntNode(imageUpdate.updateKind().getColor()));
    embed.set("footer", buildFooter());

    ArrayNode fields = objectMapper.createArrayNode();
    fields.add(buildContainerNamesField(update));
    fields.add(buildImageNamesField(imageUpdate));
    fields.add(buildUpdaterField(imageUpdate.remoteImageInfo()));
    fields.add(buildRemoteImageIdField(imageUpdate));

    embed.set("fields", fields);

    return embed;
  }

  private String getUpdateText(ShipItImageUpdate imageUpdate) {
    String text = "Update found. ";

    text += switch (imageUpdate.updateKind()) {
      case CONTAINER_USES_OUTDATED_BASE_IMAGE -> "The base image the container uses is outdated";
      case REFERENCE_IMAGE_IS_OUTDATED -> "The reference image is outdated, please pull it again";
    };

    return text;
  }

  private ObjectNode buildContainerNamesField(ShipItContainerUpdate update) {
    ObjectNode containerNames = objectMapper.createObjectNode();
    containerNames.set("name", new TextNode("Container names"));
    containerNames.set("value", new TextNode(String.join(", ", update.names())));
    containerNames.set("inline", BooleanNode.TRUE);
    return containerNames;
  }

  private ObjectNode buildImageNamesField(ShipItImageUpdate update) {
    ObjectNode imageNames = objectMapper.createObjectNode();
    imageNames.set("name", new TextNode("Affected image names"));
    imageNames.set("value", new TextNode(String.join(", ", update.sourceImageNames())));
    imageNames.set("inline", BooleanNode.TRUE);
    return imageNames;
  }

  private ObjectNode buildUpdaterField(ImageInformation imageInfo) {
    ObjectNode updaterInfo = objectMapper.createObjectNode();
    updaterInfo.set("name", new TextNode("Update information"));
    updaterInfo.set(
      "value",
      new TextNode(
        "Updated <t:%s:R> by **%s**" .formatted(
          imageInfo.lastUpdated().getEpochSecond(),
          imageInfo.lastUpdaterName()
        )
      )
    );

    return updaterInfo;
  }

  private ObjectNode buildRemoteImageIdField(ShipItImageUpdate imageUpdate) {
    ObjectNode remoteImageId = objectMapper.createObjectNode();
    remoteImageId.set("name", new TextNode("New digest"));
    remoteImageId.set("value", new TextNode("`" + imageUpdate.remoteImageId() + "`"));

    return remoteImageId;
  }

  private ObjectNode buildFooter() {
    ObjectNode footer = objectMapper.createObjectNode();
    footer.set("text", new TextNode("Made with \u2764\uFE0F"));
    return footer;
  }
}
