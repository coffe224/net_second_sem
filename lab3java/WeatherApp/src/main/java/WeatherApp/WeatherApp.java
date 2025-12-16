package WeatherApp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class WeatherApp {

    private static final Logger logger = LoggerFactory.getLogger(WeatherApp.class);

    private static final String GRAPHHOPPER_API_KEY = "15f80c44-c778-46f8-b020-e12b5ad1abd3";
    private static final String OPENWEATHER_API_KEY = "9bade7338d609d20faa86148719e9562";

    private JsonElement fetchJsonFromUrl(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestProperty("User-Agent", "WeatherApp/1.0 (s.chernykh@g.nsu.ru)");
        connection.setRequestProperty("Accept", "application/json");

        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("HTTP " + responseCode + " for URL: " + urlString);
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return JsonParser.parseString(response.toString());
        } finally {
            connection.disconnect();
        }
    }

    private CompletableFuture<String> fetchWeatherData(String latitude, String longitude) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            String weatherUrl = String.format(
                    "https://api.openweathermap.org/data/2.5/weather?lat=%s&lon=%s&appid=%s",
                    latitude.trim(), longitude.trim(), OPENWEATHER_API_KEY
            );
            logger.info("Weather API URL: {}", weatherUrl);
            try {
                JsonElement jsonElement = fetchJsonFromUrl(weatherUrl);
                JsonObject root = jsonElement.getAsJsonObject();

                String description = extractWeatherDescription(root);
                String windSpeed = extractWindSpeed(root);

                logger.info("Weather data fetched successfully");
                return String.format("Weather: %s, Wind speed: %s m/s", description, windSpeed);
            } catch (Exception e) {
                logger.error("Failed to fetch weather data", e);
                return "[Weather: Error]";
            }
        });

        return future.orTimeout(12, TimeUnit.SECONDS)
                .exceptionally(ex -> "[Weather: Timeout or error]");
    }

    private String extractWeatherDescription(JsonObject weatherData) {
        if (weatherData.has("weather") && weatherData.get("weather").isJsonArray()) {
            var weatherArray = weatherData.getAsJsonArray("weather");
            if (!weatherArray.isEmpty() && weatherArray.get(0).isJsonObject()) {
                var weatherObject = weatherArray.get(0).getAsJsonObject();
                return weatherObject.has("description") ?
                        weatherObject.get("description").getAsString() : "No description";
            }
        }
        return "N/A";
    }

    private String extractWindSpeed(JsonObject weatherData) {
        if (weatherData.has("wind") && weatherData.get("wind").isJsonObject()) {
            var wind = weatherData.getAsJsonObject("wind");
            if (wind.has("speed")) {
                return wind.get("speed").getAsString();
            }
        }
        return "N/A";
    }

    private CompletableFuture<String> fetchWikipediaSummary(String title) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
                String wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro&explaintext&format=json&titles=" + encodedTitle;
                logger.info("Wikipedia extract URL: {}", wikiUrl);

                JsonElement jsonElement = fetchJsonFromUrl(wikiUrl);
                JsonObject pages = jsonElement.getAsJsonObject()
                        .getAsJsonObject("query")
                        .getAsJsonObject("pages");
                JsonObject page = pages.entrySet().iterator().next().getValue().getAsJsonObject();

                String extract = page.has("extract") ?
                        page.get("extract").getAsString() : "No description available.";
                String normalizedTitle = page.has("title") ?
                        page.get("title").getAsString() : title;

                logger.info("Fetched Wikipedia summary for: {}", normalizedTitle);

                String truncatedExtract = extract.length() > 300 ?
                        extract.substring(0, 300) + "..." : extract;
                return String.format(">>> %s:\n%s\n", normalizedTitle, truncatedExtract);
            } catch (Exception e) {
                logger.error("Failed to fetch Wikipedia summary for: {}", title, e);
                return ">>> " + title + ":\n[Failed to load description]\n";
            }
        });
    }

    private void findNearbyPlaces(String latitude, String longitude, List<CompletableFuture<String>> futures) {
        String geosearchUrl = String.format(
                "https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsradius=1000&gslimit=8&gscoord=%s|%s&format=json",
                latitude.trim(), longitude.trim()
        );
        logger.info("Wikipedia geosearch URL: {}", geosearchUrl);

        try {
            JsonElement jsonElement = fetchJsonFromUrl(geosearchUrl);
            var places = jsonElement.getAsJsonObject()
                    .getAsJsonObject("query")
                    .getAsJsonArray("geosearch");

            logger.info("Found {} nearby places", places.size());

            for (int i = 0; i < places.size(); i++) {
                String title = places.get(i).getAsJsonObject().get("title").getAsString();
                futures.add(fetchWikipediaSummary(title));

                // Add small delay every 5 requests to be respectful to the API
                if (i > 0 && i % 5 == 0) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to fetch nearby places from Wikipedia", e);
            throw new RuntimeException(e);
        }
    }

    public List<String> run() {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        List<String> results = new ArrayList<>();

        System.out.print("Enter place: ");
        String place = new Scanner(System.in).nextLine();

        String geocodeUrl = String.format(
                "https://graphhopper.com/api/1/geocode?q=%%22%s%%22&key=%s",
                place, GRAPHHOPPER_API_KEY
        );

        logger.info("Geocoding API URL: {}", geocodeUrl);

        JsonElement jsonElement;
        try {
            jsonElement = fetchJsonFromUrl(geocodeUrl);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch geocoding data", e);
        }

        logger.info("Geocoding data retrieved successfully");

        var locationHits = jsonElement.getAsJsonObject().get("hits").getAsJsonArray();
        if (locationHits.isEmpty()) {
            System.out.println("No places found.");
            return List.of();
        }

        displayLocationOptions(locationHits);
        int selectedIndex = getValidSelection(locationHits.size());

        JsonObject selectedLocation = locationHits.get(selectedIndex).getAsJsonObject();
        String latitude = selectedLocation.getAsJsonObject("point").get("lat").getAsString();
        String longitude = selectedLocation.getAsJsonObject("point").get("lng").getAsString();

        logger.info("Selected coordinates: lat={}, lon={}", latitude, longitude);

        futures.add(fetchWeatherData(latitude, longitude));
        findNearbyPlaces(latitude, longitude, futures);

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        for (CompletableFuture<String> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                logger.error("Error retrieving result", e);
                results.add("[Error loading data]");
            }
        }

        return results;
    }

    private void displayLocationOptions(com.google.gson.JsonArray locationHits) {
        for (int i = 0; i < locationHits.size(); i++) {
            var location = locationHits.get(i).getAsJsonObject();
            System.out.printf("%d %s %s%n", i,
                    location.has("country") ? location.get("country").getAsString() : "N/A",
                    location.get("name").getAsString());
        }
    }

    private int getValidSelection(int maxIndex) {
        Scanner scanner = new Scanner(System.in);
        int selection;
        do {
            System.out.printf("Enter place number (0 to %d): ", maxIndex - 1);
            selection = scanner.nextInt();
        } while (selection < 0 || selection >= maxIndex);
        return selection;
    }

    public static void main(String[] args) {
        WeatherApp app = new WeatherApp();
        List<String> results = app.run();

        System.out.println("\n--- Results ---");
        for (String result : results) {
            System.out.println(result);
            System.out.println("—".repeat(20) + "\n");
        }
    }
}