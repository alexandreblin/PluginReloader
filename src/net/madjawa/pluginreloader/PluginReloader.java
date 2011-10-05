package net.madjawa.pluginreloader;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.logging.Logger;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Type;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.UnknownDependencyException;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginReloader extends JavaPlugin {
	
	static final Logger log = Logger.getLogger("Minecraft");
	
	@Override
	public void onEnable(){ 
		log.info("[PluginReloader] Version 0.1 enabled (by MadJawa)");
	}
	
	@Override
	public void onDisable(){ 
		log.info("[PluginReloader] Plugin disabled");
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String commandLabel, String[] args) {
		if (cmd.getName().equalsIgnoreCase("plugin")) {
			if (args.length < 1) return false;
			
			String action = args[0];
			
			if (!(action.equalsIgnoreCase("load") || action.equalsIgnoreCase("unload") || action.equalsIgnoreCase("reload"))) {
				sender.sendMessage(ChatColor.GOLD + "Invalid action specified");
				
				return false;
			}
			
			if (!sender.hasPermission("pluginreloader." + action)) {
				sender.sendMessage(ChatColor.RED + "You do not have the permission to do this");
				
				return true; // don't send the command usage
			}
			
			if (args.length == 1) {
				sender.sendMessage(ChatColor.GOLD + "You must specify at least one plugin");
				
				return true;
			}

			// reloading all the plugins specified
			for (int i = 1; i < args.length; ++i) {
				String plName = args[i];
				
				try {
					if (action.equalsIgnoreCase("unload")) {
						unloadPlugin(plName);
						
						sender.sendMessage(ChatColor.GRAY + "Unloaded " + ChatColor.RED + plName + ChatColor.GRAY + " successfully!");
					}
					else if (action.equalsIgnoreCase("load")) {
						loadPlugin(plName);
						
						sender.sendMessage(ChatColor.GRAY + "Loaded " + ChatColor.GREEN + plName + ChatColor.GRAY + " successfully!");
					}
					else if (action.equalsIgnoreCase("reload")) {
						unloadPlugin(plName);
						loadPlugin(plName);
						
						sender.sendMessage(ChatColor.GRAY + "Reloaded " + ChatColor.GREEN + plName + ChatColor.GRAY + " successfully!");
					}
				} catch (Exception e) {
					e.printStackTrace();
					sender.sendMessage(ChatColor.GRAY + "Error with " + ChatColor.RED + plName + ChatColor.GRAY + ": " + ChatColor.GOLD + getExceptionMessage(e) + ChatColor.GRAY + " (check console for more details)");
				}
			}
			
			return true;
		}
		
		return false;
	}
	
	// tries to retrieve the most precise error message
	private static String getExceptionMessage(Throwable e) {
		if (e.getCause() != null) {
			String msg = getExceptionMessage(e.getCause());
			
			if (!msg.equalsIgnoreCase(e.getClass().getName())) {
				return msg;
			}
		}
		
		if (e.getLocalizedMessage() != null) return e.getLocalizedMessage();
		else if (e.getMessage() != null) return e.getMessage();
		else if (e.getClass().getCanonicalName() != null) return e.getClass().getCanonicalName();
		else return e.getClass().getName();
	}

	@SuppressWarnings("unchecked")
	private void unloadPlugin(String pluginName) throws SecurityException, NoSuchFieldException, IllegalArgumentException, IllegalAccessException {
		PluginManager manager = getServer().getPluginManager();
		
		SimplePluginManager spm = (SimplePluginManager) manager;
		
		List<Plugin> plugins = null;
		Map<String, Plugin> lookupNames = null;
		Map<Event.Type, SortedSet<RegisteredListener>> listeners = null;
		SimpleCommandMap commandMap = null;
		Map<String, Command> knownCommands = null;
		
		if (spm != null) {
			// this is fucking ugly
			// as there is no public getters for these, and no methods to properly unload plugins
			// I have to fiddle directly in the private attributes of the plugin manager class
			Field pluginsField = spm.getClass().getDeclaredField("plugins");
			Field lookupNamesField = spm.getClass().getDeclaredField("lookupNames");
			Field listenersField = spm.getClass().getDeclaredField("listeners");
			Field commandMapField = spm.getClass().getDeclaredField("commandMap");
			
			pluginsField.setAccessible(true);
			lookupNamesField.setAccessible(true);
			listenersField.setAccessible(true);
			commandMapField.setAccessible(true);
			
			plugins = (List<Plugin>) pluginsField.get(spm);
			lookupNames = (Map<String, Plugin>) lookupNamesField.get(spm);
			listeners = (Map<Type, SortedSet<RegisteredListener>>) listenersField.get(spm);
			commandMap = (SimpleCommandMap) commandMapField.get(spm);
			
			Field knownCommandsField = commandMap.getClass().getDeclaredField("knownCommands");
			
			knownCommandsField.setAccessible(true);
			
			knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
		}
		
		// in case the same plugin is loaded multiple times (could happen)
		for (Plugin pl : manager.getPlugins()) {
			if (pl.getDescription().getName().equalsIgnoreCase(pluginName)) {
				// disable the plugin itself
				manager.disablePlugin(pl);
				
				// removing all traces of the plugin in the private structures (so it won't appear in the plugin list twice)
				if (plugins != null && plugins.contains(pl)) {
					plugins.remove(pl);
				}
				
				if (lookupNames != null && lookupNames.containsKey(pluginName)) {
					lookupNames.remove(pluginName);
				}
				
				// removing registered listeners to avoid registering them twice when reloading the plugin
				if (listeners != null) {
					for (SortedSet<RegisteredListener> set : listeners.values()) {
						for (Iterator<RegisteredListener> it = set.iterator(); it.hasNext();) {
							RegisteredListener value = it.next();
							
							if (value.getPlugin() == pl) {								
								it.remove();
							}
						}
					}
				}
				
				// removing registered commands, if we don't do this they can't get re-registered when the plugin is reloaded
				if (commandMap != null) {
					for (Iterator<Map.Entry<String, Command>> it = knownCommands.entrySet().iterator(); it.hasNext();) {
						Map.Entry<String, Command> entry = it.next();
						
						if (entry.getValue() instanceof PluginCommand) {
							PluginCommand c = (PluginCommand) entry.getValue();
							
							if (c.getPlugin() == pl) {
								c.unregister(commandMap);
								
								it.remove();
							}
						}
					}
				}
				
				try {
					ArrayList<Permission> permissionlist = pl.getDescription().getPermissions();
					Iterator p = permissionlist.iterator();
					while (p.hasNext()) {
						manager.removePermission(p.next().toString());
					}
				} catch (NoSuchMethodError e) {
					log.info("[PluginReloader] " + pluginName + " has no permissions to unload.");
				}
				
				// ta-da! we're done (hopefully)
				// I don't know if there are more things that need to be reset
				// I'll take a more in-depth look into the bukkit source if it doesn't work well
			}
		}
	}
	
	private void loadPlugin(String pluginName) throws InvalidPluginException, InvalidDescriptionException, UnknownDependencyException {
		// loading a plugin on the other hand is way simpler
		
		PluginManager manager = getServer().getPluginManager();
		
		Plugin plugin = manager.loadPlugin(new File("plugins", pluginName + ".jar"));
		
		if (plugin == null) return;
		
		manager.enablePlugin(plugin);
		
		try {
			ArrayList<Permission> permissionlist = plugin.getDescription().getPermissions();
			Iterator p = permissionlist.iterator();
			while (p.hasNext()) {
				manager.removePermission(p.next().toString());
			}
		} catch (NoSuchMethodError e) {
			log.info("[PluginReloader] " + pluginName + " has no permissions to load.");
		}
	}
}
