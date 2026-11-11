import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class WeatherAppWeb {

    // Your OpenWeatherMap API key
    private static final String API_KEY = "1f5bf323dc983f05e75a80aadf21bf46";
    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        // RENDER MODIFICATION: Read the dynamic port assigned by Render, fallback to 8080 locally
        String portEnv = System.getenv("PORT");
        int port = (portEnv != null && !portEnv.isEmpty()) ? Integer.parseInt(portEnv) : 8080;
        
        // RENDER MODIFICATION: Bind to 0.0.0.0 so Render's external network can route to it
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        // 1. Serve the Frontend UI
        server.createContext("/", (exchange) -> {
            byte[] response = getHtml().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        // 2. API Proxy: Fetch Weather
        server.createContext("/api/weather", (exchange) -> {
            String query = exchange.getRequestURI().getQuery(); 
            String owmUrl = "https://api.openweathermap.org/data/2.5/weather?" + query + "&appid=" + API_KEY + "&units=metric";
            forwardApiRequest(exchange, owmUrl);
        });

        // 3. API Proxy: Search/Autocomplete Cities
        server.createContext("/api/search", (exchange) -> {
            String query = exchange.getRequestURI().getQuery();
            String owmUrl = "https://api.openweathermap.org/geo/1.0/direct?" + query + "&limit=5&appid=" + API_KEY;
            forwardApiRequest(exchange, owmUrl);
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Web Server started on port " + port);
    }

    private static void forwardApiRequest(HttpExchange exchange, String targetUrl) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(response.statusCode(), responseBytes.length);
            
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (Exception e) {
            String error = "{\"message\":\"Internal Server Error\"}";
            exchange.sendResponseHeaders(500, error.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(error.getBytes());
            }
        }
    }

    private static String getHtml() {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Minimal Weather</title>
                <script src="https://cdn.tailwindcss.com"></script>
                <style>
                    ::-webkit-scrollbar { width: 6px; }
                    ::-webkit-scrollbar-track { background: transparent; }
                    ::-webkit-scrollbar-thumb { background: #e5e7eb; border-radius: 4px; }
                </style>
            </head>
            <body class="bg-[#F9F9F9] flex items-center justify-center min-h-screen font-sans text-gray-800 selection:bg-gray-200">
                <div class="bg-white p-10 rounded-3xl shadow-[0_8px_30px_rgb(0,0,0,0.04)] border border-gray-100 w-full max-w-sm relative">
                    <div class="relative">
                        <svg class="w-4 h-4 absolute left-0 top-3 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"></path></svg>
                        <input id="searchInput" type="text" placeholder="City or Pincode..." class="w-full pl-7 pr-4 py-2 border-b border-gray-200 focus:outline-none focus:border-gray-800 transition-colors bg-transparent text-sm mb-6 pb-3" autocomplete="off">
                        <div id="dropdown" class="absolute top-full left-0 w-full bg-white border border-gray-100 shadow-lg rounded-xl mt-1 hidden z-20 max-h-56 overflow-y-auto overflow-x-hidden">
                            <ul id="dropdownList" class="text-sm"></ul>
                        </div>
                    </div>
                    <div id="weatherContent" class="text-center hidden animate-[fadeIn_0.5s_ease-out]">
                        <h1 id="cityName" class="text-3xl font-light tracking-wide mb-1 text-gray-900"></h1>
                        <p id="condition" class="text-[10px] text-gray-400 uppercase tracking-[0.2em] mb-8 font-semibold"></p>
                        <div class="text-7xl font-light mb-8 text-gray-900 tracking-tighter"><span id="temp"></span>°</div>
                        <div class="grid grid-cols-2 gap-y-4 gap-x-2 text-xs text-gray-400 font-medium">
                            <div class="text-left">FEELS LIKE <span id="feelsLike" class="block text-gray-800 text-sm font-normal mt-1"></span></div>
                            <div class="text-right">HUMIDITY <span id="humidity" class="block text-gray-800 text-sm font-normal mt-1"></span></div>
                            <div class="text-left">WIND <span id="wind" class="block text-gray-800 text-sm font-normal mt-1"></span></div>
                        </div>
                    </div>
                    <div id="messageState" class="text-center text-gray-400 text-sm py-10">
                        Locating you...
                    </div>
                </div>
                <script>
                    const searchInput = document.getElementById('searchInput');
                    const dropdown = document.getElementById('dropdown');
                    const dropdownList = document.getElementById('dropdownList');
                    const weatherContent = document.getElementById('weatherContent');
                    const messageState = document.getElementById('messageState');
                    let debounceTimer;

                    window.onload = () => {
                        if (navigator.geolocation) {
                            navigator.geolocation.getCurrentPosition(
                                pos => fetchWeather(`lat=${pos.coords.latitude}&lon=${pos.coords.longitude}`),
                                err => showMessage("Location access denied. Please search manually.")
                            );
                        } else {
                            showMessage("Geolocation not supported. Search manually.");
                        }
                    };

                    searchInput.addEventListener('input', (e) => {
                        clearTimeout(debounceTimer);
                        const val = e.target.value.trim();
                        if (!val) { dropdown.classList.add('hidden'); return; }
                        if (/^\\d+$/.test(val)) {
                            renderDropdown([{ name: `Search Pincode: ${val}`, isZip: true, query: val }]);
                            return;
                        }
                        debounceTimer = setTimeout(() => {
                            fetch(`/api/search?q=${encodeURIComponent(val)}`)
                                .then(res => res.json())
                                .then(data => {
                                    if(data && data.length > 0) renderDropdown(data);
                                    else dropdown.classList.add('hidden');
                                });
                        }, 400);
                    });

                    function renderDropdown(items) {
                        dropdownList.innerHTML = '';
                        items.forEach(item => {
                            const li = document.createElement('li');
                            li.className = 'px-4 py-3 hover:bg-gray-50 cursor-pointer border-b border-gray-50 last:border-0 truncate transition-colors';
                            if (item.isZip) {
                                li.textContent = item.name;
                                li.onclick = () => selectLocation(`zip=${item.query}`);
                            } else {
                                const stateStr = item.state ? `, ${item.state}` : '';
                                li.textContent = `${item.name}${stateStr}, ${item.country}`;
                                li.onclick = () => selectLocation(`lat=${item.lat}&lon=${item.lon}`);
                            }
                            dropdownList.appendChild(li);
                        });
                        dropdown.classList.remove('hidden');
                    }

                    function selectLocation(queryParams) {
                        dropdown.classList.add('hidden');
                        searchInput.value = '';
                        fetchWeather(queryParams);
                    }

                    document.addEventListener('click', (e) => {
                        if (!searchInput.contains(e.target) && !dropdown.contains(e.target)) {
                            dropdown.classList.add('hidden');
                        }
                    });

                    function fetchWeather(queryParams) {
                        weatherContent.classList.add('hidden');
                        messageState.classList.remove('hidden');
                        messageState.textContent = "Fetching weather...";
                        fetch(`/api/weather?${queryParams}`)
                            .then(res => res.json())
                            .then(data => {
                                if (data.cod !== 200) {
                                    showMessage(data.message || "City not found.");
                                    return;
                                }
                                document.getElementById('cityName').textContent = data.name;
                                document.getElementById('condition').textContent = data.weather[0].main;
                                document.getElementById('temp').textContent = Math.round(data.main.temp);
                                document.getElementById('feelsLike').textContent = Math.round(data.main.feels_like) + '°';
                                document.getElementById('humidity').textContent = data.main.humidity + '%';
                                document.getElementById('wind').textContent = data.wind.speed + ' m/s';
                                messageState.classList.add('hidden');
                                weatherContent.classList.remove('hidden');
                            })
                            .catch(err => showMessage("Failed to fetch data."));
                    }

                    function showMessage(msg) {
                        weatherContent.classList.add('hidden');
                        messageState.classList.remove('hidden');
                        messageState.textContent = msg;
                    }
                </script>
            </body>
            </html>
            """;
    }
}