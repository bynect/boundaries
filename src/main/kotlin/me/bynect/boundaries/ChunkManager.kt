package me.bynect.boundaries

import me.bynect.boundaries.BoundaryManager.chunksTag
import me.bynect.boundaries.BoundaryManager.chunksType
import me.bynect.boundaries.BoundaryManager.isTracked
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.persistence.PersistentDataType
import java.nio.ByteBuffer


object ChunkManager : Listener {

    private val plugin = Bukkit.getPluginManager().getPlugin("boundaries") as Boundaries

    // This tag is used to store the owner of a chunk
    val ownerTag = NamespacedKey(plugin, "boundaryOwner")
    val ownerType = PersistentDataType.STRING

    // This tag is used to store the player who selected a chunk
    val selectTag = NamespacedKey(plugin, "boundarySelector")
    val selectType = PersistentDataType.STRING

    // This tag is used to store the permission of a chunk
    val permTag = NamespacedKey(plugin, "boundaryPermission")
    val permType = PersistentDataType.LIST.listTypeFrom(PersistentDataType.BOOLEAN)

    val permBreakBlock = 0

    private fun defaultPerms(): MutableList<Boolean> {
        return mutableListOf(false)
    }

    fun setPermission(location: Location, index: Int, value: Boolean) {
        val perms = location.chunk.persistentDataContainer.get(permTag, permType) ?: defaultPerms()
        perms[index] = value
        location.chunk.persistentDataContainer.set(permTag, permType, perms)
    }

    fun getPermission(location: Location, index: Int): Boolean {
        val perms = location.chunk.persistentDataContainer.get(permTag, permType) ?: defaultPerms()
        return perms[index]
    }

    fun serializeLocation(location: Location): ByteArray {
        return ByteBuffer.allocate(Double.SIZE_BYTES * 3 + location.world.name.length)
            .putDouble(location.x)
            .putDouble(location.y)
            .putDouble(location.z)
            .put(location.world.name.toByteArray())
            .array()
    }

    fun deserializeLocation(array: ByteArray): Location {
        val world = Bukkit.getWorld(String(array, Double.SIZE_BYTES * 3, array.size - Double.SIZE_BYTES * 3))
        val buffer = ByteBuffer.wrap(array)
        return Location(world, buffer.getDouble(), buffer.getDouble(), buffer.getDouble())
    }

    fun changeOwner(location: Location, owner: String?): Boolean {
        val pdc = location.chunk.persistentDataContainer
        if (owner == null) {
            pdc.remove(ownerTag)
        } else {
            if (pdc.has(ownerTag, ownerType))
                return false
            pdc.set(ownerTag, ownerType, owner)
        }
        return true
    }

    fun isSelectedBy(location: Location, player: Player): Boolean {
        return getSelector(location) == player.name
    }

    fun isOwned(location: Location): Boolean {
        return getOwner(location) != null
    }

    fun isOwnedBy(location: Location, player: Player): Boolean {
        return getOwner(location) == player.name
    }

    fun getOwner(location: Location): String? {
        return location.chunk.persistentDataContainer.get(ownerTag, ownerType)
    }

    fun getSelector(location: Location): String? {
        return location.chunk.persistentDataContainer.get(selectTag, selectType)
    }

    fun isSelected(location: Location): Boolean {
        return getSelector(location) != null
    }

    fun selectChunk(player: Player, location: Location) {
        val pdc = location.chunk.persistentDataContainer
        assert(!pdc.has(selectTag))
        pdc.set(selectTag, selectType, player.name)
    }

    fun deselectChunk(location: Location) {
        val pdc = location.chunk.persistentDataContainer
        assert(pdc.has(selectTag))
        pdc.remove(selectTag)
    }

    fun addGuide(player: Player, changes: MutableList<BlockState>, location: Location) {
        val material = if (isOwnedBy(location, player)) Material.LIME_CONCRETE else Material.YELLOW_CONCRETE

        for (x in (0..1)) {
            for (z in (0..1)) {
                for (y in (-64..320)) {
                    val state = location.chunk.getBlock(x * 15, y, z * 15).state
                    state.type = material
                    changes.add(state)
                }
            }
        }
    }

    fun removeGuide(changes: MutableList<BlockState>, location: Location) {
        for (x in (0..1)) {
            for (z in (0..1)) {
                for (y in (-64..320)) {
                    val state = location.chunk.getBlock(x * 15, y, z * 15).state
                    state.update(true)
                    changes.add(state)
                }
            }
        }
    }

    fun selectGuide(player: Player, location: Location) {
        val changes: MutableList<BlockState> = mutableListOf()
        addGuide(player, changes, location)
        player.sendBlockChanges(changes)
    }

    fun deselectGuide(player: Player, location: Location) {
        val changes: MutableList<BlockState> = mutableListOf()
        removeGuide(changes, location)
        player.sendBlockChanges(changes)
    }

    fun deselectAllChunks(player: Player) {
        val chunks = player.persistentDataContainer.get(chunksTag, chunksType) ?: return
        val changes: MutableList<BlockState> = mutableListOf()

        for (serialized in chunks) {
            val location = deserializeLocation(serialized)
            removeGuide(changes, location)
            deselectChunk(location)
        }

        player.sendBlockChanges(changes)
    }

    private fun updateChunkGuides(player: Player) {
        val radius = 10
        val changes: MutableList<BlockState> = mutableListOf()

        var x: Int = -16 * radius
        while (x <= 16 * radius) {
            var z: Int = -16 * radius
            while (z <= 16 * radius) {
                val block = player.location.block.getRelative(x, 0, z)
                if (isSelectedBy(block.location, player)) {
                    addGuide(player, changes, block.location)
                }

                z += 16
            }
            x += 16
        }

        player.sendBlockChanges(changes)
    }

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val chunk = event.to.chunk
        if (chunk != event.from.chunk) {
            if (isTracked(event.player)) {
                updateChunkGuides(event.player)
            } else {
                val owner = chunk.persistentDataContainer.get(ownerTag, ownerType)
                if (owner != null) {
                    event.player.sendActionBar(
                        Component
                            .text("Chunk owned by ")
                            .color(NamedTextColor.WHITE)
                            .decorate(TextDecoration.ITALIC)
                            .append(
                                Component
                                    .text(owner)
                                    .color(NamedTextColor.LIGHT_PURPLE)
                                    .decorate(TextDecoration.BOLD)
                            )
                    )
                } else {
                    event.player.sendActionBar(Component.text(""))
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val owner = getOwner(event.block.location)
        if (owner == null || owner == event.player.name)
            return

        val perms = event.block.chunk.persistentDataContainer.get(permTag , permType) ?: return
        if (perms[permBreakBlock])
            event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (isTracked(player)) {
            updateChunkGuides(player)
        }
    }
}