package pt.lsts.aiscaster;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.google.common.eventbus.Subscribe;
import com.google.gson.Gson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dk.tbsalling.ais.tracker.AISTrack;
import dk.tbsalling.ais.tracker.AISTracker;
import dk.tbsalling.ais.tracker.events.AisTrackDynamicsUpdatedEvent;

public class AisCaster {

	private AISTracker tracker;

	private String authKey;

	private final Logger logger = LoggerFactory.getLogger(AisCaster.class);

	LinkedHashMap<Long, Long> mmsiToTimestamps = new LinkedHashMap<>();

	public AisCaster(String host, int port, String authKey) throws UnknownHostException, IOException {

		this.authKey = authKey;

		Socket socket = new Socket();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				if (socket != null)
					try {
						socket.close();
					} catch (Exception e) {
						e.printStackTrace();
					}
			}
		});

		socket.connect(new InetSocketAddress(host, port));
		System.out.println("Connected to " + host + ":" + port);
		System.out.println("API key: " + authKey);
		tracker = new AISTracker();
		tracker.registerSubscriber(this);
		tracker.update(socket.getInputStream());
	}

	@Subscribe
	public void handleEvent(AisTrackDynamicsUpdatedEvent event) {
		sendData(event.getAisTrack());
	}

	private synchronized void sendData(AISTrack track) {
		if (track.getShipName() == null)
			return;

		AisShip ship = new AisShip(track.getMmsi(), track.getTimeOfLastUpdate().toEpochMilli(),
				track.getLatitude().doubleValue(), track.getLongitude().doubleValue(),
				track.getCourseOverGround().doubleValue(), track.getSpeedOverGround().doubleValue(),
				track.getTrueHeading().doubleValue(), track.getShipName(), track.getShipType().getCode());

		List<AisShip> ships = new ArrayList<>();
		ships.add(ship);

		if (coordsValid(ship.latitude, ship.longitude)) {
			try {
				String RIPPLES_URL = "https://ripples.lsts.pt/ais";
				URL url = new URL(RIPPLES_URL);
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();
				connection.setRequestProperty("Authorization", authKey);
				connection.setDoOutput(true);
				connection.setRequestMethod("POST");
				connection.setRequestProperty("Content-Type", "application/json");

				Gson gson = new Gson();
				String jsonShip = gson.toJson(ships);
				DataOutputStream out = new DataOutputStream(connection.getOutputStream());
				out.writeBytes(jsonShip);
				out.flush();
				out.close();

				Integer responseCode = connection.getResponseCode();
				if (responseCode == HttpURLConnection.HTTP_OK) {
					logger.info("Sent " + ship.toString() + " to " + RIPPLES_URL);
				} else {
					logger.info("Received response code " + responseCode + " from " + RIPPLES_URL);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			logger.warn("Received invalid ship coordinates [" + ship.latitude + "," + ship.longitude + "]");
		}
	}

	private boolean coordsValid(double latitude, double longitude) {
		return Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("Usage: java -jar AisCaster.jar <HOST>:<PORT>:<AUTH_KEY>");
			System.exit(1);
		}

		String host = args[0].split(":")[0];
		int port = Integer.parseInt(args[0].split(":")[1]);
		String authKey = args[0].split(":")[2];

		new AisCaster(host, port, authKey);
	}
}
