package net.runelite.client.plugins.lootlogger;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemDefinition;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.util.Text;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.plugins.lootlogger.data.BossTab;
import net.runelite.client.plugins.lootlogger.data.LootLog;
import net.runelite.client.plugins.lootlogger.data.Pet;
import net.runelite.client.plugins.lootlogger.data.UniqueItem;
import net.runelite.client.plugins.lootlogger.localstorage.LTItemEntry;
import net.runelite.client.plugins.lootlogger.localstorage.LTRecord;
import net.runelite.client.plugins.lootlogger.localstorage.LootRecordWriter;
import net.runelite.client.plugins.lootlogger.ui.LootLoggerPanel;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.loottracker.LootRecordType;
import org.apache.commons.lang3.ArrayUtils;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Stoned Tracker",
	description = "Local data persistence and unique UI for the Loot Tracker",
	type = PluginType.UTILITY,
	enabledByDefault = false
)
@Slf4j
public class LootLoggerPlugin extends Plugin
{
	private static final String SIRE_FONT_TEXT = "you place the unsired into the font of consumption...";
	private static final String SIRE_REWARD_TEXT = "the font consumes the unsired";
	private static final int MAX_TEXT_CHECK = 25;
	private static final int MAX_PET_TICKS = 5;

	// Kill count handling
	private static final Pattern CLUE_SCROLL_PATTERN = Pattern.compile("You have completed ([0-9]+) ([a-z]+) Treasure Trails.");
	private static final Pattern BOSS_NAME_NUMBER_PATTERN = Pattern.compile("Your (.*) kill count is:? ([0-9]*).");
	private static final Pattern NUMBER_PATTERN = Pattern.compile("([0-9]+)");

	private static final Set<String> PET_MESSAGES = Set.of("You have a funny feeling like you're being followed.",
		"You feel something weird sneaking into your backpack.",
		"You have a funny feeling like you would have been followed...");

	private static final int NMZ_MAP_REGION = 9033;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	public LootLoggerConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private LootRecordWriter writer;

	private LootLoggerPanel panel;
	private NavigationButton navButton;

	@Getter
	private SetMultimap<LootRecordType, String> lootNames = HashMultimap.create();

	private boolean prepared = false;
	private boolean unsiredReclaiming = false;
	private boolean fetchingUsername = false;
	private int unsiredCheckCount = 0;
	// Some pets aren't handled (skilling pets) so reset gotPet after a few ticks
	private int petTicks = 0;
	private boolean gotPet = false;

	private final Map<String, Integer> killCountMap = new HashMap<>();

	@Provides
	LootLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LootLoggerConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new LootLoggerPanel(itemManager, this);

		final BufferedImage icon = ImageUtil.getResourceStreamFromClass(getClass(), "panel-icon.png");

		navButton = NavigationButton.builder()
			.tooltip("Loot Logger")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();

		if (config.enableUI())
		{
			clientToolbar.addNavigation(navButton);
		}

		// Attach necessary info from item manager on load, probably a better method
		if (!prepared)
		{
			prepared = true;
			clientThread.invokeLater(() ->
			{
				switch (client.getGameState())
				{
					case UNKNOWN:
					case STARTING:
						return false;
				}

				UniqueItem.prepareUniqueItems(itemManager);
				return true;
			});
		}

		if (client.getGameState().equals(GameState.LOGGED_IN) || client.getGameState().equals(GameState.LOADING))
		{
			updateWriterUsername();
		}
	}

	@Override
	protected void shutDown()
	{
		if (config.enableUI())
		{
			clientToolbar.removeNavigation(navButton);
		}

		gotPet = false;
		petTicks = 0;
	}

	@Subscribe
	public void onConfigChanged(final ConfigChanged event)
	{
		if (event.getGroup().equals("lootlogger"))
		{
			if (event.getKey().equals("enableUI"))
			{
				if (config.enableUI())
				{
					clientToolbar.addNavigation(navButton);
				}
				else
				{
					clientToolbar.removeNavigation(navButton);
				}
			}

			if (config.enableUI())
			{
				SwingUtilities.invokeLater(panel::refreshUI);
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(final GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGING_IN)
		{
			updateWriterUsername();
		}
	}

	private void updateWriterUsername()
	{
		writer.setPlayerUsername(client.getUsername());
		localPlayerNameChanged();
	}

	private void localPlayerNameChanged()
	{
		lootNames = writer.getKnownFileNames();
		if (config.enableUI())
		{
			SwingUtilities.invokeLater(panel::showSelectionView);
		}
	}

	private Collection<LTItemEntry> convertToLTItemEntries(Collection<ItemStack> stacks)
	{
		return stacks.stream().map(i -> createLTItemEntry(i.getId(), i.getQuantity())).collect(Collectors.toList());
	}

	private LTItemEntry createLTItemEntry(final int id, final int qty)
	{
		final ItemDefinition c = itemManager.getItemDefinition(id);
		final int realId = c.getNote() == -1 ? c.getId() : c.getLinkedNoteId();
		final int price = itemManager.getItemPrice(realId);
		return new LTItemEntry(c.getName(), id, qty, price);
	}

	private void addRecord(final LTRecord record)
	{
		writer.addLootTrackerRecord(record);
		lootNames.put(record.getType(), record.getName().toLowerCase());
		if (config.enableUI())
		{
			SwingUtilities.invokeLater(() -> panel.addLog(record));
		}
	}

	@Subscribe
	public void onLootReceived(final LootReceived event)
	{
		if (isInNightmareZone() && config.ignoreNmz())
		{
			return;
		}

		final Collection<LTItemEntry> drops = convertToLTItemEntries(event.getItems());

		if (gotPet)
		{
			final Pet p = Pet.getByBossName(event.getName());
			if (p != null)
			{
				gotPet = false;
				petTicks = 0;
				drops.add(createLTItemEntry(p.getPetID(), 1));
			}
		}

		final int kc = killCountMap.getOrDefault(event.getName().toUpperCase(), -1);
		final LTRecord record = new LTRecord(event.getName(), event.getCombatLevel(), kc, event.getType(), drops);
		addRecord(record);
	}

	public Collection<LTRecord> getDataByName(LootRecordType type, String name)
	{
		final BossTab tab = BossTab.getByName(name);
		if (tab != null)
		{
			name = tab.getName();
		}

		return writer.loadLootTrackerRecords(type, name);
	}

	/**
	 * Creates a loot log for this name and then attaches it to the UI when finished
	 *
	 * @param name record name
	 */
	public void requestLootLog(final LootRecordType type, final String name)
	{
		clientThread.invoke(() ->
		{
			final Collection<LTRecord> records = getDataByName(type, name);
			final LootLog log = new LootLog(records, name);
			SwingUtilities.invokeLater(() -> panel.useLog(log));
		});
	}

	public boolean clearStoredDataByName(final LootRecordType type, final String name)
	{
		lootNames.remove(type, name);
		return writer.deleteLootTrackerRecords(type, name);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != WidgetID.DIALOG_SPRITE_GROUP_ID)
		{
			return;
		}

		Widget text = client.getWidget(WidgetInfo.DIALOG_SPRITE_TEXT);
		if (SIRE_FONT_TEXT.equals(text.getText().toLowerCase()))
		{
			unsiredCheckCount = 0;
			unsiredReclaiming = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (gotPet)
		{
			if (petTicks > MAX_PET_TICKS)
			{
				gotPet = false;
				petTicks = 0;
			}
			else
			{
				petTicks++;
			}
		}

		if (unsiredReclaiming)
		{
			if (hasUnsiredWidgetUpdated())
			{
				unsiredReclaiming = false;
				return;
			}

			unsiredCheckCount++;
			if (unsiredCheckCount >= MAX_TEXT_CHECK)
			{
				unsiredReclaiming = false;
			}
		}
	}

	// Handles checking for unsired loot reclamation
	private boolean hasUnsiredWidgetUpdated()
	{
		final Widget text = client.getWidget(WidgetInfo.DIALOG_SPRITE_TEXT);
		// Reclaimed an item?
		if (text.getText().toLowerCase().contains(SIRE_REWARD_TEXT))
		{
			final Widget sprite = client.getWidget(WidgetInfo.DIALOG_SPRITE);
			if (sprite.getItemId() == -1)
			{
				return false;
			}

			log.debug("Unsired was exchanged for item ID: {}", sprite.getItemId());
			receivedUnsiredLoot(sprite.getItemId());
			return true;
		}

		return false;
	}

	// Handles adding the unsired loot to the tracker
	private void receivedUnsiredLoot(int itemID)
	{
		clientThread.invokeLater(() ->
		{
			Collection<LTRecord> data = getDataByName(LootRecordType.NPC, BossTab.ABYSSAL_SIRE.getName());
			ItemDefinition itemDefinition = itemManager.getItemDefinition(itemID);
			LTItemEntry itemEntry = new LTItemEntry(itemDefinition.getName(), itemID, 1, 0);

			log.debug("Received Unsired item: {}", itemDefinition.getName());

			// Don't have data for sire, create a new record with just this data.
			if (data == null)
			{
				log.debug("No previous Abyssal sire loot, creating new loot record");
				LTRecord r = new LTRecord(BossTab.ABYSSAL_SIRE.getName(), 350, -1, LootRecordType.NPC, Collections.singletonList(itemEntry));
				addRecord(r);
				return;
			}

			log.debug("Adding drop to last abyssal sire loot record");
			// Add data to last kill count
			final List<LTRecord> items = new ArrayList<>(data);
			final LTRecord r = items.get(items.size() - 1);
			r.addDropEntry(itemEntry);
			writer.writeLootTrackerFile(BossTab.ABYSSAL_SIRE.getName(), items);
			if (config.enableUI())
			{
				SwingUtilities.invokeLater(panel::refreshUI);
			}
		});
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		final String chatMessage = Text.removeTags(event.getMessage());

		if (PET_MESSAGES.contains(chatMessage))
		{
			gotPet = true;
		}

		// Check if message is for a clue scroll reward
		final Matcher m = CLUE_SCROLL_PATTERN.matcher(chatMessage);
		if (m.find())
		{
			final String eventType;
			switch (m.group(2).toLowerCase())
			{
				case "beginner":
					eventType = "Clue Scroll (Beginner)";
					break;
				case "easy":
					eventType = "Clue Scroll (Easy)";
					break;
				case "medium":
					eventType = "Clue Scroll (Medium)";
					break;
				case "hard":
					eventType = "Clue Scroll (Hard)";
					break;
				case "elite":
					eventType = "Clue Scroll (Elite)";
					break;
				case "master":
					eventType = "Clue Scroll (Master)";
					break;
				default:
					return;
			}

			final int killCount = Integer.parseInt(m.group(1));
			killCountMap.put(eventType.toUpperCase(), killCount);
			return;
		}

		// Barrows KC
		if (chatMessage.startsWith("Your Barrows chest count is"))
		{
			Matcher n = NUMBER_PATTERN.matcher(chatMessage);
			if (n.find())
			{
				killCountMap.put("BARROWS", Integer.parseInt(n.group()));
				return;
			}
		}

		// Raids KC
		if (chatMessage.startsWith("Your completed Chambers of Xeric count is"))
		{
			Matcher n = NUMBER_PATTERN.matcher(chatMessage);
			if (n.find())
			{
				killCountMap.put("CHAMBERS OF XERIC", Integer.parseInt(n.group()));
				return;
			}
		}

		// Tob KC
		if (chatMessage.startsWith("Your completed Theatre of Blood count is"))
		{
			Matcher n = NUMBER_PATTERN.matcher(chatMessage);
			if (n.find())
			{
				killCountMap.put("THEATRE OF BLOOD", Integer.parseInt(n.group()));
				return;
			}
		}

		// Handle all other boss
		final Matcher boss = BOSS_NAME_NUMBER_PATTERN.matcher(chatMessage);
		if (boss.find())
		{
			final String bossName = boss.group(1);
			final int killCount = Integer.parseInt(boss.group(2));
			killCountMap.put(bossName.toUpperCase(), killCount);
		}
	}

	/**
	 * Is the player inside the NMZ arena?
	 */
	private boolean isInNightmareZone()
	{
		if (client.getLocalPlayer() == null)
		{
			return false;
		}

		// It seems that KBD shares the map region with NMZ but NMZ is never in plane 0.
		return ArrayUtils.contains(client.getMapRegions(), NMZ_MAP_REGION) && client.getLocalPlayer().getWorldLocation().getPlane() > 0;
	}
}