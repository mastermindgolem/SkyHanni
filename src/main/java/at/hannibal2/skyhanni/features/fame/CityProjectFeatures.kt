package at.hannibal2.skyhanni.features.fame

import at.hannibal2.skyhanni.SkyHanniMod
import at.hannibal2.skyhanni.config.ConfigUpdaterMigrator
import at.hannibal2.skyhanni.data.ProfileStorageData
import at.hannibal2.skyhanni.events.GuiContainerEvent
import at.hannibal2.skyhanni.events.GuiRenderEvent
import at.hannibal2.skyhanni.events.InventoryCloseEvent
import at.hannibal2.skyhanni.events.InventoryFullyOpenedEvent
import at.hannibal2.skyhanni.events.SecondPassedEvent
import at.hannibal2.skyhanni.features.inventory.bazaar.BazaarApi
import at.hannibal2.skyhanni.utils.ChatUtils
import at.hannibal2.skyhanni.utils.CollectionUtils.addAsSingletonList
import at.hannibal2.skyhanni.utils.InventoryUtils.getUpperItems
import at.hannibal2.skyhanni.utils.ItemUtils
import at.hannibal2.skyhanni.utils.ItemUtils.getLore
import at.hannibal2.skyhanni.utils.ItemUtils.itemName
import at.hannibal2.skyhanni.utils.ItemUtils.name
import at.hannibal2.skyhanni.utils.LorenzColor
import at.hannibal2.skyhanni.utils.LorenzUtils
import at.hannibal2.skyhanni.utils.NEUInternalName
import at.hannibal2.skyhanni.utils.NEUItems
import at.hannibal2.skyhanni.utils.NEUItems.getItemStack
import at.hannibal2.skyhanni.utils.NEUItems.getPrice
import at.hannibal2.skyhanni.utils.NumberUtil
import at.hannibal2.skyhanni.utils.NumberUtil.addSeparators
import at.hannibal2.skyhanni.utils.RenderUtils.highlight
import at.hannibal2.skyhanni.utils.RenderUtils.renderStringsAndItems
import at.hannibal2.skyhanni.utils.SimpleTimeMark
import at.hannibal2.skyhanni.utils.StringUtils.matchFirst
import at.hannibal2.skyhanni.utils.StringUtils.matches
import at.hannibal2.skyhanni.utils.TimeUtils
import at.hannibal2.skyhanni.utils.renderables.Renderable
import at.hannibal2.skyhanni.utils.repopatterns.RepoPattern
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.client.gui.inventory.GuiEditSign
import net.minecraft.inventory.ContainerChest
import net.minecraft.item.ItemStack
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import kotlin.time.Duration.Companion.seconds

class CityProjectFeatures {

    private var display = emptyList<List<Any>>()
    private var inInventory = false
    private var lastReminderSend = SimpleTimeMark.farPast()

    private val patternGroup = RepoPattern.group("fame.projects")
    private val contributeAgainPattern by patternGroup.pattern(
        "contribute",
        "§7Contribute again: §e(?<time>.*)"
    )
    private val completedPattern by patternGroup.pattern(
        "completed",
        "§aProject is (?:being built|released)!"
    )

    companion object {

        private val config get() = SkyHanniMod.feature.event.cityProject
        fun disable() {
            config.dailyReminder = false
            ChatUtils.chat("Disabled city project reminder messages!")
        }
    }

    @SubscribeEvent
    fun onSecondPassed(event: SecondPassedEvent) {
        if (!config.dailyReminder) return
        val playerSpecific = ProfileStorageData.playerSpecific ?: return
        if (ReminderUtils.isBusy()) return

        if (LorenzUtils.skyBlockArea == "Community Center") return

        if (playerSpecific.nextCityProjectParticipationTime == 0L) return
        if (System.currentTimeMillis() <= playerSpecific.nextCityProjectParticipationTime) return
        if (lastReminderSend.passedSince() < 30.seconds) return
        lastReminderSend = SimpleTimeMark.now()

        ChatUtils.clickableChat(
            "Daily City Project Reminder! (Click here to disable this reminder)",
            "shstopcityprojectreminder"
        )
    }

    @SubscribeEvent
    fun onInventoryClose(event: InventoryCloseEvent) {
        inInventory = false
    }

    @SubscribeEvent
    fun onInventoryOpen(event: InventoryFullyOpenedEvent) {
        if (!LorenzUtils.inSkyBlock) return

        inInventory = false
        if (!inCityProject(event)) return
        inInventory = true

        if (config.showMaterials) {
            // internal name -> amount
            val materials = mutableMapOf<NEUInternalName, Int>()
            for ((_, item) in event.inventoryItems) {
                if (item.name != "§eContribute this component!") continue
                fetchMaterials(item, materials)
            }

            display = buildList(materials)
        }

        if (config.showReady) {
            var nextTime = SimpleTimeMark.farFuture()
            val now = SimpleTimeMark.now()
            for ((_, item) in event.inventoryItems) {

                val lore = item.getLore()
                val completed = lore.lastOrNull()?.let { completedPattern.matches(it) } ?: false
                if (completed) continue
                lore.matchFirst(contributeAgainPattern) {
                    val rawTime = group("time")
                    if (!rawTime.contains("Soon!")) return@matchFirst
                    val duration = TimeUtils.getDuration(rawTime)
                    val endTime = now + duration
                    if (endTime < nextTime) {
                        nextTime = endTime
                    }
                }
                if (item.name != "§eContribute this component!") continue
                nextTime = now
            }
            ProfileStorageData.playerSpecific?.nextCityProjectParticipationTime = nextTime.toMillis()
        }
    }

    private fun inCityProject(event: InventoryFullyOpenedEvent): Boolean {
        val lore = event.inventoryItems[4]?.getLore() ?: return false
        if (lore.isEmpty()) return false
        if (lore[0] != "§8City Project") return false
        return true
    }

    private fun buildList(materials: MutableMap<NEUInternalName, Int>) = buildList<List<Any>> {
        addAsSingletonList("§7City Project Materials")

        if (materials.isEmpty()) {
            addAsSingletonList("§cNo Materials to contribute.")
            return@buildList
        }

        for ((internalName, amount) in materials) {
            val stack = internalName.getItemStack()
            val name = internalName.itemName
            val list = mutableListOf<Any>()
            list.add(" §7- ")
            list.add(stack)

            list.add(Renderable.optionalLink("$name §ex${amount.addSeparators()}", {
                if (Minecraft.getMinecraft().currentScreen is GuiEditSign) {
                    LorenzUtils.setTextIntoSign("$amount")
                } else {
                    BazaarApi.searchForBazaarItem(name, amount)
                }
            }) { inInventory && !NEUItems.neuHasFocus() })

            val price = internalName.getPrice(false) * amount
            val format = NumberUtil.format(price)
            list.add(" §7(§6$format§7)")
            add(list)
        }
    }

    private fun fetchMaterials(item: ItemStack, materials: MutableMap<NEUInternalName, Int>) {
        var next = false
        val lore = item.getLore()
        val completed = lore.lastOrNull()?.let { completedPattern.matches(it) } ?: false
        if (completed) return
        for (line in lore) {
            if (line == "§7Cost") {
                next = true
                continue
            }
            if (!next) continue
            if (line == "") break
            if (line.contains("Bits")) break

            val (name, amount) = ItemUtils.readItemAmount(line) ?: continue
            val internalName = NEUInternalName.fromItemName(name)
            val old = materials.getOrPut(internalName) { 0 }
            materials[internalName] = old + amount
        }
    }

    @SubscribeEvent
    fun onBackgroundDraw(event: GuiRenderEvent.ChestGuiOverlayRenderEvent) {
        if (!LorenzUtils.inSkyBlock) return
        if (!config.showMaterials) return
        if (!inInventory) return

        config.pos.renderStringsAndItems(display, posLabel = "City Project Materials")
    }

    @SubscribeEvent
    fun onBackgroundDrawn(event: GuiContainerEvent.BackgroundDrawnEvent) {
        if (!LorenzUtils.inSkyBlock) return
        if (!config.showReady) return
        if (!inInventory) return


        if (event.gui !is GuiChest) return
        val guiChest = event.gui
        val chest = guiChest.inventorySlots as ContainerChest

        for ((slot, stack) in chest.getUpperItems()) {
            val lore = stack.getLore()
            if (lore.isEmpty()) continue
            val last = lore.last()
            if (last == "§eClick to contribute!") {
                slot highlight LorenzColor.YELLOW
            }
        }
    }

    @SubscribeEvent
    fun onConfigFix(event: ConfigUpdaterMigrator.ConfigFixEvent) {
        event.move(2, "misc.cityProject", "event.cityProject")
    }
}
