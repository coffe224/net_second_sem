package WeatherApp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class PlaceInfoFinder {

    private static final Logger logger = LoggerFactory.getLogger(PlaceInfoFinder.class);

    private final String graphhopperAPIKey = "15f80c44-c778-46f8-b020-e12b5ad1abd3";
    private final String openweathermapAPIKey = "9bade7338d609d20faa86148719e9562";

    private String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    private JsonElement getJsonElementByUrl(String urlString) throws IOException {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(new URL(urlString).openStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                sb.append(inputLine);
            }
            return JsonParser.parseString(sb.toString());
        }
    }

    private CompletableFuture<String> getWeather(String lat, String lon) {
        return CompletableFuture.supplyAsync(() -> {
            String urlString = String.format(
                    "https://api.openweathermap.org/data/2.5/weather?lat=%s&lon=%s&appid=%s",
                    lat.trim(), lon.trim(), openweathermapAPIKey
            );
            logger.info("weather url: {}", urlString);
            try {
                JsonElement je = getJsonElementByUrl(urlString);
                JsonObject root = je.getAsJsonObject();

                // Weather description
                String description = "N/A";
                if (root.has("weather") && root.get("weather").isJsonArray()) {
                    var weatherArr = root.getAsJsonArray("weather");
                    if (!weatherArr.isEmpty() && weatherArr.get(0).isJsonObject()) {
                        var w = weatherArr.get(0).getAsJsonObject();
                        description = w.has("description") ? w.get("description").getAsString() : "no description";
                    }
                }

                // Wind speed
                String windSpeed = "N/A";
                if (root.has("wind") && root.get("wind").isJsonObject()) {
                    var wind = root.getAsJsonObject("wind");
                    if (wind.has("speed")) {
                        windSpeed = wind.get("speed").getAsString();
                    }
                }

                logger.info("weather get success");
                return String.format("Weather info: %s, Wind speed: %s m/s", description, windSpeed);
            } catch (IOException e) {
                logger.error("Failed to fetch weather", e);
                throw new RuntimeException(e);
            }
        });
    }

    // Fetch Wikipedia summary by page title
    private CompletableFuture<String> getWikipediaSummary(String title) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
                String urlString = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro&explaintext&format=json&titles=" + encodedTitle;
                logger.info("Wikipedia extract URL: {}", urlString);

                JsonElement je = getJsonElementByUrl(urlString);
                JsonObject pages = je.getAsJsonObject().getAsJsonObject("query").getAsJsonObject("pages");
                // Get first (and only) page
                JsonObject page = pages.entrySet().iterator().next().getValue().getAsJsonObject();

                String extract = page.has("extract") ? page.get("extract").getAsString() : "No description available.";
                String normalizedTitle = page.has("title") ? page.get("title").getAsString() : title;

                logger.info("Fetched Wikipedia summary for: {}", normalizedTitle);
                return String.format("📍 %s:\n%s\n", normalizedTitle, extract.length() > 300 ? extract.substring(0, 300) + "..." : extract);
            } catch (Exception e) {
                logger.error("Failed to fetch Wikipedia summary for: " + title, e);
                return "📍 " + title + ":\n[Failed to load description]\n";
            }
        });
    }

    // Use Wikipedia Geosearch instead of OpenTripMap
    private void getInterestPlacesNear(String lat, String lon, List<CompletableFuture<String>> list) {
        String urlString = String.format(
                "https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gsradius=1000&gslimit=8&gscoord=%s|%s&format=json",
                lat.trim(), lon.trim()
        );
        logger.info("Wikipedia geosearch URL: {}", urlString);
        try {
            JsonElement je = getJsonElementByUrl(urlString);
            var geosearch = je.getAsJsonObject()
                    .getAsJsonObject("query")
                    .getAsJsonArray("geosearch");

            logger.info("Found {} nearby places", geosearch.size());

            for (int i = 0; i < geosearch.size(); i++) {
                String title = geosearch.get(i).getAsJsonObject().get("title").getAsString();
                list.add(getWikipediaSummary(title));

                // Optional: small delay to be polite to Wikipedia API
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

    public List<String> work() {
        List<CompletableFuture<String>> futuresList = new ArrayList<>();
        List<String> textList = new ArrayList<>();

        System.out.println("Enter place");
        String place = new Scanner(System.in).nextLine();

        String urlString = String.format(
                "https://graphhopper.com/api/1/geocode?q=%%22%s%%22&key=%s",
                place, graphhopperAPIKey
        );

        logger.info("graphhopper url: {}", urlString);

        JsonElement je;
        try {
            je = getJsonElementByUrl(urlString);
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch geocoding data", e);
        }

        logger.info("Get data success");

        var hits = je.getAsJsonObject().get("hits").getAsJsonArray();
        if (hits.isEmpty()) {
            System.out.println("No places found.");
            return List.of();
        }

        for (int i = 0; i < hits.size(); i++) {
            var obj = hits.get(i).getAsJsonObject();
            System.out.printf("%d %s %s%n", i,
                    obj.has("country") ? obj.get("country").getAsString() : "N/A",
                    obj.get("name").getAsString());
        }

        logger.info("Get place success");

        int number;
        do {
            System.out.printf("Enter place number. (0 to %d)%n", hits.size() - 1);
            number = new Scanner(System.in).nextInt();
        } while (number < 0 || number >= hits.size());

        String lat = hits.get(number).getAsJsonObject().getAsJsonObject("point").get("lat").getAsString();
        String lon = hits.get(number).getAsJsonObject().getAsJsonObject("point").get("lng").getAsString();

        logger.info("Coordinates: lat={}, lon={}", lat, lon);

        futuresList.add(getWeather(lat, lon));
        getInterestPlacesNear(lat, lon, futuresList);

        // Wait for all async tasks
        CompletableFuture.allOf(futuresList.toArray(new CompletableFuture[0])).join();

        // Collect results
        for (CompletableFuture<String> future : futuresList) {
            try {
                textList.add(future.get());
            } catch (Exception e) {
                logger.error("Error retrieving result", e);
                textList.add("[Error loading data]");
            }
        }

        return textList;
    }

    public static void main(String[] args) {
        PlaceInfoFinder finder = new PlaceInfoFinder();
        List<String> results = finder.work();
        for (String result : results) {
            System.out.println(result);
            System.out.println("—" + "\n");
        }
    }
}