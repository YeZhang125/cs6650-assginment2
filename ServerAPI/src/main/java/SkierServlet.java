import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@WebServlet( name = "SkierServlet", urlPatterns = "/skiers/*")
public class SkierServlet extends HttpServlet {

  private static final String HOST = "35.92.148.147";
  private static final int POOL_SIZE = 100;  // Number of pre-created channels
  private static final int QUEUE_COUNT = 1; // Number of queues
  private Connection connection;
  private BlockingQueue<Channel> channelPool;
  private Gson gson = new Gson();
  private static final String QUEUE_PREFIX = "skier_queue_";
  @Override
  public void init() throws ServletException {
    try {
      ConnectionFactory factory = new ConnectionFactory();
      factory.setHost(HOST);
      factory.setUsername("test_user");
      factory.setPassword("test_password");
      this.connection = factory.newConnection();

      // Initialize channel pool
      channelPool = new ArrayBlockingQueue<>(POOL_SIZE);
      for (int i = 0; i < POOL_SIZE; i++) {
        channelPool.add(connection.createChannel());
      }

      // Declare queues dynamically
      try (Channel channel = connection.createChannel()) {
        for (int i = 1; i <= QUEUE_COUNT; i++) {
          channel.queueDeclare(QUEUE_PREFIX + i, true, false, false, null);
        }
      }

    } catch (IOException | TimeoutException e) {
      throw new ServletException("Failed to connect to RabbitMQ", e);
    }
  }


  @Override
  protected void doPost(HttpServletRequest request, HttpServletResponse resp) throws IOException {
    resp.setContentType("application/json");

    if (request.getPathInfo() == null) {
      sendErrorResponse(resp, "Invalid URL format!");
      return;
    }

    String[] url = request.getPathInfo().split("/");
    if (url.length != 8) {
      sendErrorResponse(resp, "Invalid URL format! Expected: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}");
      return;
    }

    int resortID = parseInt(url[1], resp, "Invalid resortID!");
    String dayID = url[5];
    int skierID = parseInt(url[7], resp, "Invalid skierID!");

    if (!dayID.matches("^([1-9]|[1-9][0-9]|[12][0-9][0-9]|3[0-5][0-9]|36[0-6])$")) {
      sendErrorResponse(resp, "Invalid day ID!");
      return;
    }

    if (skierID < 1 || skierID > 100000 || resortID < 1 || resortID > 10) {
      sendErrorResponse(resp, "Invalid skierID or resortID!");
      return;
    }

    String requestBody = readRequestBody(request);
    System.out.println("Request body: " + requestBody);


    if (requestBody == null || requestBody.isEmpty()) {
      sendErrorResponse(resp, "Request body is empty.");
      return;
    }

    // Validate if the body contains valid JSON
    JsonObject jsonObject = null;
    try {
      jsonObject = gson.fromJson(requestBody, JsonObject.class);
      if (jsonObject == null || !jsonObject.isJsonObject()) {
        sendErrorResponse(resp, "Invalid JSON format.");
        return;
      }
    } catch (JsonSyntaxException e) {
      sendErrorResponse(resp, "Invalid JSON syntax.");
      return;
    }

    if (!jsonObject.has("liftID") || !jsonObject.has("time")) {
      sendErrorResponse(resp, "Missing required fields (liftID, time)");
      return;
    }
    //Select queue dynamically (Hashing-Based)
    String queueName = getQueueForSkier(skierID);

    // Publish message to RabbitMQ
    try {
      Channel channel = channelPool.poll(500, TimeUnit.MILLISECONDS);
      if (channel == null) {
        sendErrorResponse(resp, "Failed to get a RabbitMQ channel from the pool.");
        return;
      }

      AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
              .contentType("application/json")
              .build();

      channel.basicPublish("", queueName, properties, requestBody.getBytes(StandardCharsets.UTF_8));
      channelPool.add(channel);

      sendSuccessResponse(resp, "Skier processed successfully in queue: " + queueName);
    } catch (Exception e) {
      sendErrorResponse(resp, "Failed to send message to RabbitMQ: " + e.getMessage());
    }
  }

  private String getQueueForSkier(int skierID) {
    int queueIndex = skierID % QUEUE_COUNT; // Ensures skier always goes to the same queue
    return QUEUE_PREFIX + (queueIndex + 1);
  }

  @Override
  public void destroy() {
    try {
      if (connection != null) {
        for (Channel channel : channelPool) {
          channel.close();
        }
        connection.close();
      }
    } catch (IOException | TimeoutException e) {
      System.out.println("Failed to close RabbitMQ connection");
    }
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setContentType("text/html");
    response.setStatus(HttpServletResponse.SC_OK);
    PrintWriter out = response.getWriter();
    out.println("<h1>It works! :)</h1>");
  }

  // Helper Methods
  private int parseInt(String value, HttpServletResponse resp, String errorMsg) throws IOException {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      sendErrorResponse(resp, errorMsg);
      return -1;
    }
  }

  private String readRequestBody(HttpServletRequest request) throws IOException {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader = request.getReader()) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    }
    return sb.toString();
  }

  private void sendErrorResponse(HttpServletResponse resp, String message) throws IOException {
    resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    resp.getWriter().write(gson.toJson(new ErrorResponse(message)));
  }

  private void sendSuccessResponse(HttpServletResponse resp, String message) throws IOException {
    resp.setStatus(HttpServletResponse.SC_CREATED);
    resp.getWriter().write(gson.toJson(new SuccessResponse(message)));
  }

  static class ErrorResponse {
    String message;
    ErrorResponse(String message) { this.message = message; }
  }

  static class SuccessResponse {
    String message;
    SuccessResponse(String message) { this.message = message; }
  }

}
