package openblocks.common.tileentity;

import com.google.common.base.Optional;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import openblocks.OpenBlocks;
import openblocks.client.gui.GuiAutoAnvil;
import openblocks.common.LiquidXpUtils;
import openblocks.common.container.ContainerAutoAnvil;
import openblocks.common.tileentity.TileEntityAutoAnvil.AutoSlots;
import openmods.api.IHasGui;
import openmods.api.INeighbourAwareTile;
import openmods.api.IValueProvider;
import openmods.api.IValueReceiver;
import openmods.gui.misc.IConfigurableGuiSlots;
import openmods.include.IncludeInterface;
import openmods.inventory.GenericInventory;
import openmods.inventory.IInventoryProvider;
import openmods.inventory.ItemMover;
import openmods.inventory.TileEntityInventory;
import openmods.liquids.SidedFluidCapabilityWrapper;
import openmods.sync.SyncMap;
import openmods.sync.SyncableFlags;
import openmods.sync.SyncableSides;
import openmods.sync.SyncableTank;
import openmods.tileentity.SyncedTileEntity;
import openmods.utils.EnchantmentUtils;
import openmods.utils.MiscUtils;
import openmods.utils.SidedInventoryAdapter;
import openmods.utils.SidedItemHandlerAdapter;
import openmods.utils.VanillaAnvilLogic;
import openmods.utils.bitmap.BitMapUtils;
import openmods.utils.bitmap.IRpcDirectionBitMap;
import openmods.utils.bitmap.IRpcIntBitMap;
import openmods.utils.bitmap.IWriteableBitMap;

public class TileEntityAutoAnvil extends SyncedTileEntity implements IHasGui, IInventoryProvider, IConfigurableGuiSlots<AutoSlots>, INeighbourAwareTile, ITickable {

	protected static final int TOTAL_COOLDOWN = 40;
	public static final int MAX_STORED_LEVELS = 45;
	public static final int TANK_CAPACITY = LiquidXpUtils.getLiquidForLevel(MAX_STORED_LEVELS);

	private int cooldown = 0;

	private boolean needsTankUpdate;

	/**
	 * The 3 slots in the inventory
	 */
	public enum Slots {
		tool,
		modifier,
		output
	}

	/**
	 * The keys of the things that can be auto injected/extracted
	 */
	public enum AutoSlots {
		tool,
		modifier,
		output,
		xp
	}

	/**
	 * The shared/syncable objects
	 */
	private SyncableSides toolSides;
	private SyncableSides modifierSides;
	private SyncableSides outputSides;
	private SyncableSides xpSides;
	private SyncableTank tank;
	private SyncableFlags automaticSlots;

	private final GenericInventory inventory = registerInventoryCallback(new TileEntityInventory(this, "autoanvil", true, 3) {
		@Override
		public boolean isItemValidForSlot(int i, ItemStack itemstack) {
			// TODO 1.11 verify - tool stuff
			if (i == 0 && (itemstack.getItem().getToolClasses(itemstack).isEmpty() && itemstack.getItem() != Items.ENCHANTED_BOOK)) { return false; }
			if (i == 2) { return false; }
			return super.isItemValidForSlot(i, itemstack);
		}
	});

	@IncludeInterface(ISidedInventory.class)
	private final SidedInventoryAdapter slotSides = new SidedInventoryAdapter(inventory);

	private final SidedFluidCapabilityWrapper tankCapability = SidedFluidCapabilityWrapper.wrap(tank, xpSides, false, true);

	private final SidedItemHandlerAdapter itemHandlerCapability = new SidedItemHandlerAdapter(inventory.getHandler());

	public TileEntityAutoAnvil() {
		slotSides.registerSlot(Slots.tool, toolSides, true, false);
		slotSides.registerSlot(Slots.modifier, modifierSides, true, false);
		slotSides.registerSlot(Slots.output, outputSides, false, true);

		itemHandlerCapability.registerSlot(Slots.tool, toolSides, true, false);
		itemHandlerCapability.registerSlot(Slots.modifier, modifierSides, true, false);
		itemHandlerCapability.registerSlot(Slots.output, outputSides, false, true);
	}

	@Override
	protected void createSyncedFields() {
		toolSides = new SyncableSides();
		modifierSides = new SyncableSides();
		outputSides = new SyncableSides();
		xpSides = new SyncableSides();
		tank = new SyncableTank(TANK_CAPACITY, OpenBlocks.Fluids.xpJuice);
		automaticSlots = SyncableFlags.create(AutoSlots.values().length);
	}

	@Override
	protected void onSyncMapCreate(SyncMap syncMap) {
		syncMap.addSyncListener(itemHandlerCapability.createSyncListener());
	}

	@Override
	public void update() {
		if (!world.isRemote) {
			// if we should auto-drink liquid, do it!
			if (automaticSlots.get(AutoSlots.xp)) {
				if (needsTankUpdate) {
					tank.updateNeighbours(world, getPos());
					needsTankUpdate = false;
				}

				tank.fillFromSides(100, world, getPos(), xpSides.getValue());
			}

			final ItemMover mover = new ItemMover(world, pos).breakAfterFirstTry().randomizeSides().setMaxSize(1);

			if (shouldAutoOutput() && hasOutput()) {
				mover.setSides(outputSides.getValue()).pushFromSlot(inventory.getHandler(), Slots.output.ordinal());
			}

			if (shouldAutoInputTool() && !hasTool()) {
				mover.setSides(toolSides.getValue()).pullToSlot(inventory.getHandler(), Slots.tool.ordinal());
			}

			if (shouldAutoInputModifier()) {
				mover.setSides(modifierSides.getValue()).pullToSlot(inventory.getHandler(), Slots.modifier.ordinal());

			}

			if (cooldown-- < 0 && !hasOutput()) {
				repairItem();
				cooldown = TOTAL_COOLDOWN;
			}

			if (tank.isDirty()) sync();
		}
	}

	private void repairItem() {
		final VanillaAnvilLogic helper = new VanillaAnvilLogic(inventory.getStackInSlot(Slots.tool), inventory.getStackInSlot(Slots.modifier), false, Optional.<String> absent());

		final ItemStack output = helper.getOutputStack();
		if (!output.isEmpty()) {
			int levelCost = helper.getLevelCost();
			int xpCost = EnchantmentUtils.getExperienceForLevel(levelCost);
			int liquidXpCost = LiquidXpUtils.xpToLiquidRatio(xpCost);

			FluidStack drained = tank.drain(liquidXpCost, false);

			if (drained != null && drained.amount == liquidXpCost) {
				tank.drain(liquidXpCost, true);
				removeModifiers(helper.getModifierCost());
				inventory.setInventorySlotContents(Slots.tool.ordinal(), ItemStack.EMPTY);
				inventory.setInventorySlotContents(Slots.output.ordinal(), output);
				playSoundAtBlock(SoundEvents.BLOCK_ANVIL_USE, 0.3f, 1f);
			}
		}
	}

	private void removeModifiers(int modifierCost) {
		if (modifierCost > 0) {
			ItemStack modifierStack = inventory.getStackInSlot(Slots.modifier);
			if (!modifierStack.isEmpty()) {
				modifierStack.shrink(modifierCost);
				inventory.setInventorySlotContents(Slots.modifier.ordinal(), modifierStack);
			}
		}
	}

	@Override
	public boolean canOpenGui(EntityPlayer player) {
		return true;
	}

	@Override
	public Object getServerGui(EntityPlayer player) {
		return new ContainerAutoAnvil(player.inventory, this);
	}

	@Override
	public Object getClientGui(EntityPlayer player) {
		return new GuiAutoAnvil(new ContainerAutoAnvil(player.inventory, this));
	}

	public IValueProvider<FluidStack> getFluidProvider() {
		return tank;
	}

	private boolean shouldAutoInputModifier() {
		return automaticSlots.get(AutoSlots.modifier);
	}

	public boolean shouldAutoOutput() {
		return automaticSlots.get(AutoSlots.output);
	}

	private boolean hasTool() {
		return !inventory.getStackInSlot(0).isEmpty();
	}

	private boolean shouldAutoInputTool() {
		return automaticSlots.get(AutoSlots.tool);
	}

	private boolean hasOutput() {
		return !inventory.getStackInSlot(2).isEmpty();
	}

	@Override
	public IInventory getInventory() {
		return slotSides;
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag) {
		tag = super.writeToNBT(tag);
		inventory.writeToNBT(tag);
		return tag;
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		inventory.readFromNBT(tag);
	}

	private SyncableSides selectSlotMap(AutoSlots slot) {
		switch (slot) {
			case modifier:
				return modifierSides;
			case output:
				return outputSides;
			case tool:
				return toolSides;
			case xp:
				return xpSides;
			default:
				throw MiscUtils.unhandledEnum(slot);
		}
	}

	@Override
	public IValueProvider<Set<EnumFacing>> createAllowedDirectionsProvider(AutoSlots slot) {
		return selectSlotMap(slot);
	}

	@Override
	public IWriteableBitMap<EnumFacing> createAllowedDirectionsReceiver(AutoSlots slot) {
		SyncableSides dirs = selectSlotMap(slot);
		return BitMapUtils.createRpcAdapter(createRpcProxy(dirs, IRpcDirectionBitMap.class));
	}

	@Override
	public IValueProvider<Boolean> createAutoFlagProvider(AutoSlots slot) {
		return BitMapUtils.singleBitProvider(automaticSlots, slot.ordinal());
	}

	@Override
	public IValueReceiver<Boolean> createAutoSlotReceiver(AutoSlots slot) {
		IRpcIntBitMap bits = createRpcProxy(automaticSlots, IRpcIntBitMap.class);
		return BitMapUtils.singleBitReceiver(bits, slot.ordinal());
	}

	@Override
	public void validate() {
		super.validate();
		this.needsTankUpdate = true;
	}

	@Override
	public void onNeighbourChanged(BlockPos neighbourPos, Block neighbourBlock) {
		this.needsTankUpdate = true;
	}

	@Override
	public boolean hasCapability(Capability<?> capability, EnumFacing facing) {
		if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return tankCapability.hasHandler(facing);

		if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
			return itemHandlerCapability.hasHandler(facing);

		return super.hasCapability(capability, facing);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getCapability(Capability<T> capability, EnumFacing facing) {
		if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return (T)tankCapability.getHandler(facing);

		if (capability == CapabilityItemHandler.ITEM_HANDLER_CAPABILITY)
			return (T)itemHandlerCapability.getHandler(facing);

		return super.getCapability(capability, facing);
	}

}
