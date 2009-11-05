/**
 * New BSD License
 * http://www.opensource.org/licenses/bsd-license.php
 * Copyright (c) 2009, RaptorProject (http://code.google.com/p/raptor-chess-interface/)
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 * Neither the name of the RaptorProject nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package raptor.connector.ics;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import raptor.Raptor;
import raptor.chat.ChatEvent;
import raptor.chat.ChatType;
import raptor.chess.BughouseGame;
import raptor.chess.Game;
import raptor.chess.Move;
import raptor.chess.pgn.PgnHeader;
import raptor.chess.util.GameUtils;
import raptor.connector.Connector;
import raptor.connector.ConnectorListener;
import raptor.connector.ics.timeseal.TimesealingSocket;
import raptor.pref.PreferenceKeys;
import raptor.pref.RaptorPreferenceStore;
import raptor.script.ChatScript;
import raptor.script.ChatScriptContext;
import raptor.script.GameScriptContext;
import raptor.script.ScriptConnectorType;
import raptor.script.ScriptContext;
import raptor.script.ChatScript.ChatScriptType;
import raptor.service.BughouseService;
import raptor.service.ChatService;
import raptor.service.GameService;
import raptor.service.ScriptService;
import raptor.service.SeekService;
import raptor.service.SoundService;
import raptor.service.ThreadService;
import raptor.service.GameService.GameServiceAdapter;
import raptor.service.GameService.GameServiceListener;
import raptor.service.ScriptService.ScriptServiceListener;
import raptor.swt.chat.ChatConsoleWindowItem;
import raptor.swt.chat.ChatUtils;
import raptor.swt.chat.controller.BughousePartnerController;
import raptor.swt.chat.controller.ChannelController;
import raptor.swt.chat.controller.MainController;
import raptor.swt.chat.controller.PersonController;
import raptor.swt.chat.controller.RegExController;
import raptor.swt.chess.ChessBoardUtils;
import raptor.util.BrowserUtils;
import raptor.util.RaptorStringTokenizer;

/**
 * An ics (internet chess server) connector. You will need to supply yuor own
 * IcsConnectorContext because they are all different. You might also need to
 * override some methods in order to get it working.
 */
public abstract class IcsConnector implements Connector {
	private static final Log LOG = LogFactory.getLog(IcsConnector.class);

	protected class IcsChatScriptContext extends IcsScriptContext implements
			ChatScriptContext {
		ChatEvent event;
		boolean ignoreEvent = false;

		public IcsChatScriptContext(ChatEvent event) {
			this.event = event;
		}

		public IcsChatScriptContext(String... params) {
			super(params);
		}

		public String getMessage() {
			if (event == null) {
				Raptor.getInstance().alert(
						"getMessage is not supported in toolbar scripts");
				return null;
			} else {
				return event.getMessage();
			}
		}

		public String getMessageChannel() {
			if (event == null) {
				Raptor
						.getInstance()
						.alert(
								"getMessageChannel is not supported in toolbar scripts");
				return null;
			} else {
				return event.getChannel();
			}
		}

		public String getMessageSource() {
			if (event == null) {
				Raptor.getInstance().alert(
						"getMessageSource is not supported in toolbar scripts");
				return null;
			} else {
				return event.getSource();
			}
		}

	}

	protected class IcsScriptContext implements ScriptContext {

		protected String[] parameters;

		protected IcsScriptContext(String... parameters) {
			this.parameters = parameters;
		}

		public void alert(String message) {
			Raptor.getInstance().alert(message);
		}

		public String[] getParameters() {
			return parameters;
		}

		public long getPingMillis() {
			return lastPingTime;
		}

		public String getUserFollowing() {
			Raptor.getInstance().alert(
					"getUserFollowing is not yet implemented.");
			return "";
		}

		public int getUserIdleSeconds() {
			return (int) (System.currentTimeMillis() - lastSendTime) / 1000;
		}

		public String getUserName() {
			return userName;
		}

		public String getValue(String key) {
			return scriptHash.get(key);
		}

		public void launchProcess(String... commandAndArgs) {
			try {
				Runtime.getRuntime().exec(commandAndArgs);
			} catch (Throwable t) {
				onError("Error launching process: "
						+ Arrays.toString(commandAndArgs), t);
			}
		}

		public void openChannelTab(String channel) {
			if (!Raptor.getInstance().getWindow().containsChannelItem(
					IcsConnector.this, channel)) {
				ChatConsoleWindowItem windowItem = new ChatConsoleWindowItem(
						new ChannelController(IcsConnector.this, channel));
				Raptor.getInstance().getWindow().addRaptorWindowItem(
						windowItem, false);
				ChatUtils.appendPreviousChatsToController(windowItem
						.getConsole());
			}
		}

		public void openPartnerTab() {
			if (!Raptor.getInstance().getWindow().containsPartnerTellItem(
					IcsConnector.this)) {
				ChatConsoleWindowItem windowItem = new ChatConsoleWindowItem(
						new BughousePartnerController(IcsConnector.this));
				Raptor.getInstance().getWindow().addRaptorWindowItem(
						windowItem, false);
				ChatUtils.appendPreviousChatsToController(windowItem
						.getConsole());
			}
		}

		public void openPersonTab(String person) {
			if (!Raptor.getInstance().getWindow().containsPersonalTellItem(
					IcsConnector.this, person)) {
				ChatConsoleWindowItem windowItem = new ChatConsoleWindowItem(
						new PersonController(IcsConnector.this, person));
				Raptor.getInstance().getWindow().addRaptorWindowItem(
						windowItem, false);
				ChatUtils.appendPreviousChatsToController(windowItem
						.getConsole());
			}
		}

		public void openRegExTab(String regularExpression) {
			if (!Raptor.getInstance().getWindow().containsPartnerTellItem(
					IcsConnector.this)) {
				ChatConsoleWindowItem windowItem = new ChatConsoleWindowItem(
						new RegExController(IcsConnector.this,
								regularExpression));
				Raptor.getInstance().getWindow().addRaptorWindowItem(
						windowItem, false);
				ChatUtils.appendPreviousChatsToController(windowItem
						.getConsole());
			}
		}

		public void openUrl(String url) {
			BrowserUtils.openUrl(url);
		}

		public void playBughouseSound(String soundName) {
			SoundService.getInstance().playBughouseSound(soundName);
		}

		public void playSound(String soundName) {
			SoundService.getInstance().playSound(soundName);
		}

		public String prompt(String message) {
			return Raptor.getInstance().promptForText(message);
		}

		public void send(String message) {
			IcsConnector.this.sendMessage(message);
		}

		public void sendHidden(String message) {
			if (message.contains("tell")) {
				IcsConnector.this.sendMessage(message, true, ChatType.TOLD);
			} else {
				IcsConnector.this.sendMessage(message, true);
			}
		}

		public void sendToConsole(String message) {
			publishEvent(new ChatEvent(null, ChatType.INTERNAL, message));
		}

		public void speak(String message) {
			SoundService.getInstance().textToSpeech(message);
		}

		public void storeValue(String key, String value) {
			scriptHash.put(key, value);
		}

		public String urlEncode(String stringToEncode) {
			try {
				return URLEncoder.encode(stringToEncode, "UTF-8");
			} catch (UnsupportedEncodingException uee) {
			}// Eat it wont happen.
			return stringToEncode;
		}
	}

	protected BughouseService bughouseService;

	protected ChatService chatService;
	protected List<ConnectorListener> connectorListeners = Collections
			.synchronizedList(new ArrayList<ConnectorListener>(10));
	protected IcsConnectorContext context;
	protected String currentProfileName;
	protected Thread daemonThread;
	protected GameService gameService;
	protected Map<String, String> scriptHash = new HashMap<String, String>();
	protected SeekService seekService;
	/**
	 * Adds the game windows to the RaptorAppWindow.
	 */
	protected GameServiceListener gameServiceListener = new GameServiceAdapter() {

		@Override
		public void gameCreated(Game game) {
			if (game instanceof BughouseGame) {
				if (isSimulBugConnector) {
					// Always make white the primary and black the other board.
					if (StringUtils.equals(game.getHeader(PgnHeader.White),
							getUserName())) {
						ChessBoardUtils.openBoard(IcsUtils.buildController(
								game, IcsConnector.this));
					} else {
						ChessBoardUtils.openBoard(IcsUtils.buildController(
								game, IcsConnector.this), true);
					}
				} else if (((BughouseGame) game).getOtherBoard() == null) {
					ChessBoardUtils.openBoard(IcsUtils.buildController(game,
							IcsConnector.this));

				} else {
					ChessBoardUtils.openBoard(IcsUtils.buildController(game,
							IcsConnector.this, true), true);
				}
			} else {
				ChessBoardUtils.openBoard(IcsUtils.buildController(game,
						IcsConnector.this));
			}
		}
	};

	protected boolean hasSentLogin = false;
	protected boolean hasVetoPower = true;

	protected boolean hasSentPassword = false;
	protected List<ChatType> ignoringChatTypes = new ArrayList<ChatType>();
	protected StringBuilder inboundMessageBuffer = new StringBuilder(25000);
	protected ByteBuffer inputBuffer = ByteBuffer.allocate(25000);
	protected ReadableByteChannel inputChannel;
	protected boolean isConnecting;
	protected boolean isLoggedIn = false;
	protected boolean isSimulBugConnector = false;
	protected String simulBugPartnerName;

	protected Runnable keepAlive = new Runnable() {
		public void run() {
			if (isConnected()
					&& getPreferences().getBoolean(
							context.getShortName() + "-keep-alive")) {
				if (LOG.isInfoEnabled()) {
					LOG.info("In keep alive ...");
				}
				if (System.currentTimeMillis() - lastSendTime > 1000 * 60 * 50) {
					sendMessage("date", true);
					publishEvent(new ChatEvent("", ChatType.INTERNAL,
							"The \"date\" command was just sent as a keep alive."));
				} else {
					if (LOG.isInfoEnabled()) {
						LOG
								.info("No need to send keep alive rescheduling for 30 minutes.");
					}
				}
				ThreadService.getInstance()
						.scheduleOneShot(1000 * 60 * 5, this);
			}
		}
	};
	protected long lastPingTime;
	protected long lastSendTime;
	protected long lastSendPingTime;
	protected ChatConsoleWindowItem mainConsoleWindowItem;
	protected Socket socket;
	protected String userName;
	protected String userFollowing;
	protected String[] bughouseSounds = SoundService.getInstance()
			.getBughouseSoundKeys();
	protected ChatScript[] personTellMessageScripts = null;
	protected ChatScript[] channelTellMessageScripts = null;
	protected ChatScript[] partnerTellMessageScripts = null;
	protected ScriptServiceListener scriptServiceListener = new ScriptServiceListener() {

		public void onChatScriptsChanged() {
			if (isConnected()) {
				refreshChatScripts();
			}
		}

		public void onGameScriptsChanged() {
		}
	};

	/**
	 * Constructs an IcsConnector with the specified context.
	 * 
	 * @param context
	 */
	protected IcsConnector(IcsConnectorContext context) {
		this.context = context;
		chatService = new ChatService(this);
		seekService = new SeekService(this);
		gameService = new GameService();
		gameService.addGameServiceListener(gameServiceListener);
		setBughouseService(new BughouseService(this));
	}

	public void acceptSeek(String adId) {
		sendMessage("play " + adId, true);
	}

	/**
	 * Adds a connector listener to the connector.
	 */
	public void addConnectorListener(ConnectorListener listener) {
		connectorListeners.add(listener);
	}

	public String[] breakUpMessage(StringBuilder message) {
		if (message.length() <= 330) {
			return new String[] { message + "\n" };
		} else {
			int firstSpace = message.indexOf(" ");
			List<String> result = new ArrayList<String>(5);
			if (firstSpace != -1) {
				int secondSpace = message.indexOf(" ", firstSpace + 1);
				if (secondSpace != -1) {
					String beginingText = message.substring(0, secondSpace + 1);
					String wrappedText = WordUtils.wrap(message.toString(),
							330, "\n", true);
					String[] wrapped = wrappedText.split("\n");
					result.add(wrapped[0] + "\n");
					for (int i = 1; i < wrapped.length; i++) {
						result.add(beginingText + wrapped[i] + "\n");
					}
				} else {
					result.add(message.substring(0, 330) + "\n");
					publishEvent(new ChatEvent(
							null,
							ChatType.INTERNAL,
							"Your message was too long and Raptor could not find a nice "
									+ "way to break it up. Your message was trimmed to:\n"
									+ result.get(0)));
				}
			} else {
				result.add(message.substring(0, 330) + "\n");
				publishEvent(new ChatEvent(
						null,
						ChatType.INTERNAL,
						"Your message was too long and Raptor could not find a nice "
								+ "way to break it up. Your message was trimmed to:\n"
								+ result.get(0)));
			}
			return result.toArray(new String[0]);
		}
	}

	/**
	 * Connects to ics using the settings in preferences.
	 */
	public void connect() {
		connect(Raptor.getInstance().getPreferences().getString(
				context.getPreferencePrefix() + "profile"));
	}

	/**
	 * Disconnects from the ics.
	 */
	public void disconnect() {
		synchronized (this) {
			if (isConnected()) {
				try {
					ScriptService.getInstance().removeScriptServiceListener(
							scriptServiceListener);
					if (inputChannel != null) {
						try {
							inputChannel.close();
						} catch (Throwable t) {
						}
					}
					if (socket != null) {
						try {
							socket.close();
						} catch (Throwable t) {
						}
					}

					if (daemonThread != null) {
						try {
							if (daemonThread.isAlive()) {
								daemonThread.interrupt();
							}
						} catch (Throwable t) {
						}
					}
					if (keepAlive != null) {
						ThreadService.getInstance().getExecutor().remove(
								keepAlive);
					}
				} catch (Throwable t) {
				} finally {
					socket = null;
					inputChannel = null;
					daemonThread = null;
					isSimulBugConnector = false;
					simulBugPartnerName = null;
				}

				try {
					String messageLeftInBuffer = drainInboundMessageBuffer();
					if (StringUtils.isNotBlank(messageLeftInBuffer)) {
						parseMessage(messageLeftInBuffer);
					}

				} catch (Throwable t) {
				} finally {
				}
			}

			isConnecting = false;

			publishEvent(new ChatEvent(null, ChatType.INTERNAL, "Disconnected"));

			Raptor.getInstance().getWindow().setPingTime(this, -1);
			fireDisconnected();
			LOG.info("Disconnected from " + getShortName());
		}

	}

	public void dispose() {
		if (isConnected()) {
			disconnect();
		}
		if (connectorListeners != null) {
			connectorListeners.clear();
			connectorListeners = null;
		}

		if (chatService != null) {
			chatService.dispose();
			chatService = null;
		}
		if (gameService != null) {
			gameService.removeGameServiceListener(gameServiceListener);
			gameService.dispose();
			gameService = null;
		}

		if (inputBuffer != null) {
			inputBuffer.clear();
			inputBuffer = null;
		}
		if (keepAlive != null) {
			ThreadService.getInstance().getExecutor().remove(keepAlive);
		}

		if (inputChannel != null) {
			try {
				inputChannel.close();
			} catch (Throwable t) {
			}
			inputChannel = null;
		}

		LOG.info("Disposed " + getShortName() + "Connector");
	}

	public BughouseService getBughouseService() {
		return bughouseService;
	}

	public String[][] getChannelActions(String channel) {
		return new String[][] {
				new String[] { "Add Channel " + channel, "+channel " + channel },
				new String[] { "Remove Channel " + channel,
						"-channel " + channel },
				new String[] { "In Channel " + channel, "in " + channel } };
	}

	public String getChannelTabPrefix(String channel) {
		return "tell " + channel + " ";
	}

	public ChatScriptContext getChatScriptContext(String... params) {
		return new IcsChatScriptContext(params);
	}

	public ChatService getChatService() {
		return chatService;
	}

	public IcsConnectorContext getContext() {
		return context;
	}

	public String getDescription() {
		return context.getDescription();
	}

	public String[][] getGameIdActions(String gameId) {
		return new String[][] {
				new String[] { "Observe game " + gameId, "observe " + gameId },
				new String[] { "All observers in game " + gameId,
						"allobs " + gameId },
				{ "Move list for game " + gameId, "moves " + gameId } };
	}

	public GameService getGameService() {
		return gameService;
	}

	public GameScriptContext getGametScriptContext() {
		return null;
	}

	public String getPartnerTellPrefix() {
		return "ptell ";
	}

	public String[][] getPersonActions(String person) {
		return new String[][] {
				new String[] { "Finger " + person, "finger " + person },
				new String[] { "Observe " + person, "observe " + person },
				new String[] { "History " + person, "history " + person },
				new String[] { "Follow " + person, "follow " + person },
				new String[] { "Partner " + person, "partner " + person },
				new String[] { "Vars " + person, "vars " + person },
				new String[] { "Censor " + person, "+censor " + person },
				new String[] { "Uncensor " + person, "-censor " + person },
				new String[] { "Noplay " + person, "+noplay " + person },
				new String[] { "Unnoplay " + person, "-noplay " + person } };
	}

	public String getPersonTabPrefix(String person) {
		return "tell " + person + " ";
	}

	public RaptorPreferenceStore getPreferences() {
		return Raptor.getInstance().getPreferences();
	}

	public String getPrompt() {
		return context.getPrompt();
	}

	public ScriptConnectorType getScriptConnectorType() {
		return ScriptConnectorType.ICS;
	}

	public ScriptContext getScriptContext(String... params) {
		return new IcsScriptContext(params);
	}

	public SeekService getSeekService() {
		return seekService;
	}

	public String getShortName() {
		return context.getShortName();
	}

	public String getSimulBugPartnerName() {
		return simulBugPartnerName;
	}

	public String getTellToString(String handle) {
		return "tell " + handle + " ";
	}

	/**
	 * Returns the name of the current user logged in.
	 */
	public String getUserName() {
		return userName;
	}

	public boolean isConnected() {
		return socket != null && inputChannel != null && daemonThread != null;
	}

	public boolean isConnecting() {
		return isConnecting;
	}

	public boolean isLikelyChannel(String word) {
		return IcsUtils.isLikelyChannel(word);
	}

	public boolean isLikelyGameId(String word) {
		return IcsUtils.isLikelyGameId(word);
	}

	public boolean isLikelyPartnerTell(String outboundMessage) {
		return StringUtils.startsWithIgnoreCase(outboundMessage, "pt");
	}

	public boolean isLikelyPerson(String word) {
		return IcsUtils.isLikelyPerson(word);
	}

	public boolean isSimulBugConnector() {
		return isSimulBugConnector;
	}

	public void makeMove(Game game, Move move) {
		sendMessage(move.getLan(), true);
	}

	public void matchBughouse(String playerName, boolean isRated, int time,
			int inc) {
		sendMessage("$$match " + playerName + " " + time + " " + inc + " "
				+ (isRated ? "rated" : "unrated") + " bughouse");
	}

	public void onAbortKeyPress() {
		sendMessage("$$abort", true);
	}

	public void onAcceptKeyPress() {
		sendMessage("$$accept", true);
	}

	/**
	 * Auto logs in if that is configured.
	 */
	public void onAutoConnect() {
		if (Raptor.getInstance().getPreferences().getBoolean(
				context.getPreferencePrefix() + "auto-connect")) {
			connect();
		}
	}

	public void onDeclineKeyPress() {
		sendMessage("$$decline", true);
	}

	public void onDraw(Game game) {
		sendMessage("$$draw", true);
	}

	public void onError(String message) {
		onError(message, null);
	}

	public void onError(String message, Throwable t) {
		LOG.error(message, t);
		String errorMessage = IcsUtils
				.cleanupMessage("Critical error occured! We are trying to make Raptor "
						+ "bug free and we need your help! Please take a moment to report this "
						+ "error at\nhttp://code.google.com/p/raptor-chess-interface/issues/list\n\n Issue: "
						+ message
						+ (t == null ? "" : "\n"
								+ ExceptionUtils.getFullStackTrace(t)));
		publishEvent(new ChatEvent(null, ChatType.INTERNAL, errorMessage));
	}

	public void onExamineModeBack(Game game) {
		sendMessage("$$back", true);
	}

	public void onExamineModeCommit(Game game) {
		sendMessage("$$commit", true);
	}

	public void onExamineModeFirst(Game game) {
		sendMessage("$$back 300", true);
	}

	public void onExamineModeForward(Game game) {
		sendMessage("$$forward 1", true);
	}

	public void onExamineModeLast(Game game) {
		sendMessage("$$forward 300", true);
	}

	public void onExamineModeRevert(Game game) {
		sendMessage("$$revert", true);
	}

	public void onObserveGame(String gameId) {
		sendMessage("$$observe " + gameId, true);
	}

	public void onPartner(String bugger) {
		sendMessage("$$partner " + bugger, true);
	}

	public void onRematchKeyPress() {
		sendMessage("$$rematch", true);
	}

	/**
	 * Resigns the specified game.
	 */
	public void onResign(Game game) {
		sendMessage("$$resign", true);
	}

	public void onSetupClear(Game game) {
		sendMessage("$$bsetup clear", true);
	}

	public void onSetupClearSquare(Game game, int square) {
		sendMessage("$$x@" + GameUtils.getSan(square), true);
	}

	public void onSetupComplete(Game game) {
		sendMessage("bsetup done", true);
	}

	public void onSetupFromFEN(Game game, String fen) {
		sendMessage("$$bsetup fen " + fen, true);
	}

	public void onSetupStartPosition(Game game) {
		sendMessage("$$bsetup start", true);
	}

	public void onUnexamine(Game game) {
		sendMessage("$$unexamine", true);
	}

	public void onUnobserve(Game game) {
		sendMessage("$$unobs " + game.getId(), true);
	}

	public String parseChannel(String word) {
		return IcsUtils.stripChannel(word);
	}

	public String parseGameId(String word) {
		return IcsUtils.stripGameId(word);
	}

	public String parsePerson(String word) {
		return IcsUtils.stripWord(word);
	}

	/**
	 * Removes a connector listener from the connector.
	 */
	public void removeConnectorListener(ConnectorListener listener) {
		connectorListeners.remove(listener);
	}

	public String removeLineBreaks(String message) {
		return IcsUtils.removeLineBreaks(message);
	}

	public void sendBugAvailableTeamsMessage() {
		sendMessage("$$bugwho p", true, ChatType.BUGWHO_AVAILABLE_TEAMS);
	}

	public void sendBugGamesMessage() {
		sendMessage("$$bugwho g", true, ChatType.BUGWHO_GAMES);
	}

	public void sendBugUnpartneredBuggersMessage() {
		sendMessage("$$bugwho u", true, ChatType.BUGWHO_UNPARTNERED_BUGGERS);
	}

	public void sendGetSeeksMessage() {
		sendMessage("$$sought all", true, ChatType.SEEKS);
	}

	public void sendMessage(String message) {
		sendMessage(message, false, null);
	}

	/**
	 * Sends a message to the connector. A ChatEvent of OUTBOUND type should
	 * only be published if isHidingFromUser is false.
	 */
	public void sendMessage(String message, boolean isHidingFromUser) {
		sendMessage(message, isHidingFromUser, null);
	}

	/**
	 * Sends a message to the connector. A ChatEvent of OUTBOUND type should
	 * only be published containing the message if isHidingFromUser is false.
	 * The next message the connector reads in that is of the specified type
	 * should not be published to the ChatService.
	 */
	public void sendMessage(String message, boolean isHidingFromUser,
			ChatType hideNextChatType) {
		if (isConnected()) {

			if (vetoMessage(message)) {
				return;
			}

			StringBuilder builder = new StringBuilder(message);
			IcsUtils.filterOutbound(builder);

			if (LOG.isDebugEnabled()) {
				LOG.debug(context.getShortName() + "Connector Sending: "
						+ builder.toString().trim());
			}

			if (hideNextChatType != null) {
				ignoringChatTypes.add(hideNextChatType);
			}

			// Only one thread at a time should write to the socket.
			synchronized (socket) {
				try {
					String[] messages = breakUpMessage(builder);
					for (String current : messages) {
						socket.getOutputStream().write(current.getBytes());
						socket.getOutputStream().flush();
					}
					if (message.startsWith("$$")) {
						// Don't update last send time on a $$ since idle time
						// isn't effected on the server.
						lastSendPingTime = System.currentTimeMillis();
					} else {
						lastSendTime = lastSendPingTime = System
								.currentTimeMillis();
					}

				} catch (Throwable t) {
					publishEvent(new ChatEvent(null, ChatType.INTERNAL,
							"Error: " + t.getMessage()));
					disconnect();
				}
			}

			if (!isHidingFromUser) {
				publishEvent(new ChatEvent(null, ChatType.OUTBOUND, message
						.trim()));
			}
		} else {
			publishEvent(new ChatEvent(null, ChatType.INTERNAL,
					"Error: Unable to send " + message + " to "
							+ getShortName()
							+ ". There is currently no connection."));
		}
	}

	public void setSimulBugConnector(boolean isSimulBugConnector) {
		this.isSimulBugConnector = isSimulBugConnector;
	}

	public void setSimulBugPartnerName(String simulBugPartnerName) {
		this.simulBugPartnerName = simulBugPartnerName;
	}

	/**
	 * Connects with the specified profile name.
	 */
	protected void connect(final String profileName) {
		if (isConnected()) {
			throw new IllegalStateException("You are already connected to "
					+ getShortName() + " . Disconnect before invoking connect.");
		}

		resetConnectionStateVars();

		currentProfileName = profileName;

		final String profilePrefix = context.getPreferencePrefix()
				+ profileName + "-";

		if (LOG.isDebugEnabled()) {
			LOG.debug("Profile " + currentProfileName + " Prefix="
					+ profilePrefix);
		}

		if (mainConsoleWindowItem == null) {
			// Add the main console tab to the raptor window.
			createMainConsoleWindowItem();
			Raptor.getInstance().getWindow().addRaptorWindowItem(
					mainConsoleWindowItem, false);
		} else if (!Raptor.getInstance().getWindow().isBeingManaged(
				mainConsoleWindowItem)) {
			// Add a new main console to the raptor window since the existing
			// one is no longer being managed (it was already disposed).
			createMainConsoleWindowItem();
			Raptor.getInstance().getWindow().addRaptorWindowItem(
					mainConsoleWindowItem, false);
		}
		// If its being managed no need to do anything it should adjust itself
		// as the state of the connector changes from the ConnectorListener it
		// registered.

		if (LOG.isInfoEnabled()) {
			LOG.info(getShortName() + " Connecting to "
					+ getPreferences().getString(profilePrefix + "server-url")
					+ " " + getPreferences().getInt(profilePrefix + "port"));
		}
		publishEvent(new ChatEvent(null, ChatType.INTERNAL, "Connecting to "
				+ getPreferences().getString(profilePrefix + "server-url")
				+ " "
				+ getPreferences().getInt(profilePrefix + "port")
				+ (getPreferences().getBoolean(
						profilePrefix + "timeseal-enabled") ? " with "
						: " without ") + "timeseal ..."));

		ThreadService.getInstance().run(new Runnable() {
			public void run() {
				try {
					if (getPreferences().getBoolean(
							profilePrefix + "timeseal-enabled")) {
						// TO DO: rewrite TimesealingSocket to use a
						// SocketChannel.
						socket = new TimesealingSocket(
								getPreferences().getString(
										profilePrefix + "server-url"),
								getPreferences().getInt(profilePrefix + "port"),
								getInitialTimesealString());

						publishEvent(new ChatEvent(null, ChatType.INTERNAL,
								"Timeseal connection string "
										+ getInitialTimesealString()));
					} else {
						socket = new Socket(getPreferences().getString(
								profilePrefix + "server-url"), getPreferences()
								.getInt(profilePrefix + "port"));
					}
					publishEvent(new ChatEvent(null, ChatType.INTERNAL,
							"Connected"));

					inputChannel = Channels.newChannel(socket.getInputStream());

					SoundService.getInstance().playSound("alert");

					daemonThread = new Thread(new Runnable() {
						public void run() {
							messageLoop();
						}
					});
					daemonThread.setName("FicsConnectorDaemon");
					daemonThread.setPriority(Thread.MAX_PRIORITY);
					daemonThread.start();

					if (LOG.isInfoEnabled()) {
						LOG.info(getShortName() + " Connection successful");
					}
				} catch (Throwable ce) {
					publishEvent(new ChatEvent(null, ChatType.INTERNAL,
							"Error: " + ce.getMessage()));
					disconnect();
					return;
				}
			}
		});
		isConnecting = true;

		if (getPreferences().getBoolean(context.getShortName() + "-keep-alive")) {
			ThreadService.getInstance().scheduleOneShot(30 * 60 * 1000,
					keepAlive);
		}

		ScriptService.getInstance().addScriptServiceListener(
				scriptServiceListener);
		refreshChatScripts();

		fireConnecting();
	}

	protected void createMainConsoleWindowItem() {
		mainConsoleWindowItem = new ChatConsoleWindowItem(new MainController(
				this));
	}

	/**
	 * Removes all of the characters from inboundMessageBuffer and returns the
	 * string removed.
	 */
	protected String drainInboundMessageBuffer() {
		return drainInboundMessageBuffer(inboundMessageBuffer.length());
	}

	/**
	 * Removes characters 0-index from inboundMessageBuffer and returns the
	 * string removed.
	 */
	protected String drainInboundMessageBuffer(int index) {
		String result = inboundMessageBuffer.substring(0, index);
		inboundMessageBuffer.delete(0, index);
		return result;
	}

	protected void fireConnected() {
		ThreadService.getInstance().run(new Runnable() {
			public void run() {
				synchronized (connectorListeners) {
					for (ConnectorListener listener : connectorListeners) {
						listener.onConnect();
					}
				}
			}
		});
	}

	protected void fireConnecting() {
		ThreadService.getInstance().run(new Runnable() {
			public void run() {
				synchronized (connectorListeners) {
					for (ConnectorListener listener : connectorListeners) {
						listener.onConnecting();
					}
				}
			}
		});
	}

	protected void fireDisconnected() {
		ThreadService.getInstance().run(new Runnable() {
			public void run() {
				synchronized (connectorListeners) {
					for (ConnectorListener listener : connectorListeners) {
						listener.onConnecting();
					}
				}
			}
		});
	}

	protected String getInitialTimesealString() {
		return Raptor.getInstance().getPreferences().getString(
				PreferenceKeys.TIMESEAL_INIT_STRING);
	}

	/**
	 * The messageLoop. Reads the inputChannel and then invokes publishInput
	 * with the text read. Should really never be invoked.
	 */
	protected void messageLoop() {
		try {
			while (true) {
				if (isConnected()) {
					int numRead = inputChannel.read(inputBuffer);
					if (numRead > 0) {
						if (LOG.isDebugEnabled()) {
							LOG.debug(context.getShortName() + "Connector "
									+ "Read " + numRead + " bytes.");
						}
						inputBuffer.rewind();
						byte[] bytes = new byte[numRead];
						inputBuffer.get(bytes);
						inboundMessageBuffer.append(IcsUtils
								.cleanupMessage(new String(bytes)));
						try {
							onNewInput();
						} catch (Throwable t) {
							onError(context.getShortName() + "Connector "
									+ "Error in DaemonRun.onNewInput", t);
						}
						inputBuffer.clear();
					} else {
						if (LOG.isDebugEnabled()) {
							LOG.debug(context.getShortName() + "Connector "
									+ "Read 0 bytes disconnecting.");
						}
						disconnect();
						break;
					}
					Thread.sleep(50);
				} else {
					if (LOG.isDebugEnabled()) {
						LOG.debug(context.getShortName() + "Connector "
								+ "Not connected disconnecting.");
					}
					disconnect();
					break;
				}
			}
		} catch (Throwable t) {
			if (t instanceof IOException) {
				LOG
						.debug(
								context.getShortName()
										+ "Connector "
										+ "IOException occured in messageLoop (These are common when disconnecting and ignorable)",
								t);
			} else {
				onError(context.getShortName()
						+ "Connector Error in DaemonRun Thwoable", t);
			}
			disconnect();
		} finally {
			LOG.debug(context.getShortName() + "Connector Leaving readInput");
		}
	}

	/**
	 * Processes a login message. Handles sending the user name and password
	 * information and the enter if prompted to hit enter if logging in as a
	 * guest.
	 * 
	 * @param message
	 * @param isLoginPrompt
	 */
	protected void onLoginEvent(String message, boolean isLoginPrompt) {
		String profilePrefix = context.getPreferencePrefix()
				+ currentProfileName + "-";
		if (isLoginPrompt) {
			if (getPreferences().getBoolean(profilePrefix + "is-anon-guest")
					&& !hasSentLogin) {
				parseMessage(message);
				hasSentLogin = true;
				sendMessage("guest", true);
			} else if (!hasSentLogin) {
				parseMessage(message);
				hasSentLogin = true;
				String handle = getPreferences().getString(
						profilePrefix + "user-name");
				if (StringUtils.isNotBlank(handle)) {
					sendMessage(handle, true);
				}
			} else {
				parseMessage(message);
			}
		} else {
			if (getPreferences().getBoolean(profilePrefix + "is-anon-guest")
					&& !hasSentPassword) {
				hasSentPassword = true;
				parseMessage(message);
				sendMessage("", true);
			} else if (getPreferences().getBoolean(
					profilePrefix + "is-named-guest")
					&& !hasSentPassword) {
				hasSentPassword = true;
				parseMessage(message);
				sendMessage("", true);
			} else if (!hasSentPassword) {
				hasSentPassword = true;
				parseMessage(message);
				String password = getPreferences().getString(
						profilePrefix + "password");
				if (StringUtils.isNotBlank(password)) {
					// Don't show the users password.
					sendMessage(password, true);
				}
			} else {
				parseMessage(message);
			}
		}
	}

	/**
	 * This method is invoked by the run method when there is new text to be
	 * handled. It buffers text until a prompt is found then invokes
	 * parseMessage.
	 * 
	 * This method also handles login logic which is tricky.
	 */
	protected void onNewInput() {

		if (lastSendPingTime != 0) {
			ThreadService.getInstance().run(new Runnable() {
				public void run() {
					long currentTime = System.currentTimeMillis();
					lastPingTime = currentTime - lastSendPingTime;
					Raptor.getInstance().getWindow().setPingTime(
							IcsConnector.this, lastPingTime);
					lastSendPingTime = 0;
				}
			});
		}
		if (isLoggedIn) {

			// If we are logged in. Then parse out all the text between the
			// prompts.
			int promptIndex = -1;
			while ((promptIndex = inboundMessageBuffer.indexOf(context
					.getRawPrompt())) != -1) {
				String message = drainInboundMessageBuffer(promptIndex
						+ context.getRawPrompt().length());
				parseMessage(message);
			}
		} else {

			// We are not logged in.
			// There are several complex cases here depending on the prompt
			// we are waiting on.
			// There is a login prompt, a password prompt, an enter prompt,
			// and also you have to handle invalid logins.
			int loggedInMessageIndex = inboundMessageBuffer.indexOf(context
					.getLoggedInMessage());
			if (loggedInMessageIndex != -1) {
				int nameStartIndex = inboundMessageBuffer.indexOf(context
						.getLoggedInMessage())
						+ context.getLoggedInMessage().length();
				int endIndex = inboundMessageBuffer.indexOf("****",
						nameStartIndex);
				if (endIndex != -1) {
					userName = IcsUtils.stripTitles(inboundMessageBuffer
							.substring(nameStartIndex, endIndex).trim());
					LOG.info(context.getShortName() + "Connector "
							+ "login complete. userName=" + userName);
					isLoggedIn = true;
					onSuccessfulLogin();
					// Since we are now logged in, just buffer the text
					// received and
					// invoke parseMessage when the prompt arrives.
				} else {
					// We have yet to receive the **** closing message so
					// wait
					// until it arrives.
				}
			} else {
				int loginIndex = inboundMessageBuffer.indexOf(context
						.getLoginPrompt());
				if (loginIndex != -1) {
					String event = drainInboundMessageBuffer(loginIndex
							+ context.getLoginPrompt().length());
					onLoginEvent(event, true);
				} else {
					int enterPromptIndex = inboundMessageBuffer.indexOf(context
							.getEnterPrompt());
					if (enterPromptIndex != -1) {
						String event = drainInboundMessageBuffer(enterPromptIndex
								+ context.getEnterPrompt().length());
						onLoginEvent(event, false);
					} else {
						int passwordPromptIndex = inboundMessageBuffer
								.indexOf(context.getPasswordPrompt());
						if (passwordPromptIndex != -1) {
							String event = drainInboundMessageBuffer(passwordPromptIndex
									+ context.getPasswordPrompt().length());
							onLoginEvent(event, false);

						} else {
							int errorMessageIndex = inboundMessageBuffer
									.indexOf(context.getLoginErrorMessage());
							if (errorMessageIndex != -1) {
								String event = drainInboundMessageBuffer();
								parseMessage(event);
							}
						}
					}
				}
			}
		}
	}

	protected abstract void onSuccessfulLogin();

	/**
	 * Processes a message. If the user is logged in, message will be all of the
	 * text received since the last prompt from fics. If the user is not logged
	 * in, message will be all of the text received since the last login prompt.
	 * 
	 * message will always use \n as the line delimiter.
	 */
	protected void parseMessage(String message) {
		ChatEvent[] events = null;
		try {
			events = context.getParser().parse(message);
		} catch (RuntimeException re) {
			throw new RuntimeException("Error occured parsing message: "
					+ message, re);
		}

		for (ChatEvent event : events) {
			event.setMessage(IcsUtils
					.maciejgFormatToUnicode(event.getMessage()));
			publishEvent(event);
		}
	}

	/**
	 * Plays the bughouse sound for the specified ptell. Sets hasBeenHandled to
	 * true on the event if a bughouse sound is played.
	 */
	protected void playBughouseSounds(ChatEvent event) {
		if (getPreferences().getBoolean(PreferenceKeys.APP_SOUND_ENABLED)) {
			String ptell = event.getMessage();
			int colonIndex = ptell.indexOf(":");
			if (colonIndex != -1) {
				String message = ptell
						.substring(colonIndex + 1, ptell.length()).trim();
				RaptorStringTokenizer tok = new RaptorStringTokenizer(message,
						"\n", true);
				message = tok.nextToken().trim();
				for (String bugSound : bughouseSounds) {
					if (bugSound.equalsIgnoreCase(message)) {
						event.setHasBeenHandled(true);
						// This launches it on another thread.
						SoundService.getInstance().playBughouseSound(bugSound);
						break;
					}
				}
			} else {
				onError("Received a ptell event without a colon",
						new Exception());
			}
		}
	}

	/**
	 * Processes the scripts for the specified chat event. Script processing is
	 * kicked off on a different thread.
	 */
	protected void processScripts(final ChatEvent event) {
		ThreadService.getInstance().run(new Runnable() {
			public void run() {
				if (personTellMessageScripts != null
						&& event.getType() == ChatType.TELL) {
					for (ChatScript script : personTellMessageScripts) {
						ChatScript newScript = ScriptService.getInstance()
								.getChatScript(script.getName());
						if (newScript != null) {
							newScript.execute(new IcsChatScriptContext(event));
						}
					}
				} else if (channelTellMessageScripts != null
						&& event.getType() == ChatType.CHANNEL_TELL) {
					for (ChatScript script : channelTellMessageScripts) {
						ChatScript newScript = ScriptService.getInstance()
								.getChatScript(script.getName());
						if (newScript != null) {
							newScript.execute(new IcsChatScriptContext(event));
						}
					}
				} else if (partnerTellMessageScripts != null
						&& event.getType() == ChatType.PARTNER_TELL) {
					for (ChatScript script : partnerTellMessageScripts) {
						ChatScript newScript = ScriptService.getInstance()
								.getChatScript(script.getName());
						if (newScript != null) {
							newScript.execute(new IcsChatScriptContext(event));
						}
					}
				}
			}
		});
	}

	/**
	 * Publishes the specified event to the chat service. Currently all messages
	 * are published on separate threads via ThreadService.
	 */
	protected void publishEvent(final ChatEvent event) {
		if (chatService != null) { // Could have been disposed.
			if (LOG.isDebugEnabled()) {
				LOG.debug("Publishing event : " + event);
			}

			if (event.getType() == ChatType.PARTNERSHIP_DESTROYED) {
				isSimulBugConnector = false;
				simulBugPartnerName = null;
			}

			// Sets the user following. This is used in the IcsParser to
			// determine if white is on top or not.
			if (event.getType() == ChatType.FOLLOWING) {
				userFollowing = event.getSource();
			} else if (event.getType() == ChatType.NOT_FOLLOWING) {
				userFollowing = null;
			}

			if (event.getType() == ChatType.PARTNER_TELL) {
				playBughouseSounds(event);
			}

			if (!event.hasBeenHandled()
					&& (event.getType() == ChatType.PARTNER_TELL
							|| event.getType() == ChatType.TELL || event
							.getType() == ChatType.CHANNEL_TELL)) {
				processScripts(event);
			}

			int ignoreIndex = ignoringChatTypes.indexOf(event.getType());
			if (ignoreIndex != -1) {
				ignoringChatTypes.remove(ignoreIndex);
			} else {
				// It is interesting to note messages are handled sequentially
				// up to this point. chatService will publish the event
				// asynchronously.
				chatService.publishChatEvent(event);
			}
		}
	}

	protected void refreshChatScripts() {
		personTellMessageScripts = ScriptService.getInstance().getChatScripts(
				this, ChatScriptType.OnPersonTellMessages);
		channelTellMessageScripts = ScriptService.getInstance().getChatScripts(
				this, ChatScriptType.onChannelTellMessages);
		partnerTellMessageScripts = ScriptService.getInstance().getChatScripts(
				this, ChatScriptType.OnPartnerTellMessages);
	}

	/**
	 * Resets state variables related to the connection state.
	 */
	protected void resetConnectionStateVars() {
		isLoggedIn = false;
		hasSentLogin = false;
		hasSentPassword = false;
		userFollowing = null;
		isSimulBugConnector = false;
		simulBugPartnerName = null;
		ignoringChatTypes.clear();
	}

	protected void setUserFollowing(String userFollowing) {
		this.userFollowing = userFollowing;
	}

	protected boolean vetoMessage(String message) {
		if (!hasVetoPower) {
			return false;
		}
		boolean result = false;
		if (message.startsWith("set ptime")) {
			publishEvent(new ChatEvent(null, ChatType.INTERNAL,
					"Raptor will not work with ptime set. Command vetoed."));
			return true;
		}
		return result;
	}

	private void setBughouseService(BughouseService bughouseService) {
		this.bughouseService = bughouseService;
	}
}
