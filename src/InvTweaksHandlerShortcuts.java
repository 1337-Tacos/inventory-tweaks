import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * 
 * @author Jimeo Wan
 *
 */
public class InvTweaksHandlerShortcuts extends InvTweaksObfuscation {

    private static final int DROP_SLOT = -999;
    private static final Logger log = Logger.getLogger("InvTweaks");
    
    private ShortcutType defaultAction = ShortcutType.MOVE_ONE_STACK;
    private ShortcutType defaultDestination = null;
    
    // Context attributes
    private InvTweaksConfig config;
    private InvTweaksContainerManager container;
    private InvTweaksContainerSection fromSection;
    private int fromIndex;
    private ul fromStack;
    private InvTweaksContainerSection toSection;
    private ShortcutType shortcutType;

    /**
     * Allows to monitor the keys related to shortcuts
     */
    private Map<Integer, Boolean> shortcutKeysStatus;
    
    /**
     * Stores shortcuts mappings
     */
    private Map<ShortcutType, List<Integer>> shortcuts;
    
    private enum ShortcutType {
        MOVE_TO_SPECIFIC_HOTBAR_SLOT,
        MOVE_ONE_STACK,
        MOVE_ONE_ITEM,
        MOVE_ALL_ITEMS,
        MOVE_UP,
        MOVE_DOWN,
        MOVE_TO_EMPTY_SLOT,
        DROP
    }

    public InvTweaksHandlerShortcuts(Minecraft mc, InvTweaksConfig config) {
        super(mc);
        this.config = config;
        reset();
    }
    
    public void reset() {
        
        shortcutKeysStatus = new HashMap<Integer, Boolean>();
        shortcuts = new HashMap<ShortcutType, List<Integer>>();
        
        Map<String, String> keys = config.getProperties(
                InvTweaksConfig.PROP_SHORTCUT_PREFIX);
        for (String key : keys.keySet()) {
            
            String value = keys.get(key);
            
            if (value.equals(InvTweaksConfig.VALUE_DEFAULT)) {
                // Customize default behaviour
                ShortcutType newDefault = propNameToShortcutType(key);
                if (newDefault == ShortcutType.MOVE_ALL_ITEMS
                        || newDefault == ShortcutType.MOVE_ONE_ITEM
                        || newDefault == ShortcutType.MOVE_ONE_STACK) {
                    defaultAction = newDefault;
                }
                else if (newDefault == ShortcutType.MOVE_DOWN
                        || newDefault == ShortcutType.MOVE_UP) {
                    defaultDestination = newDefault;
                }
            }
            else {
                // Register shortcut mappings
                String[] keyNames = keys.get(key).split("[ ]*,[ ]*");
                List<Integer> keyBindings = new LinkedList<Integer>();
                for (String keyName : keyNames) {
                    // - Accept both KEY_### and ###, in case someone
                    //   takes the LWJGL Javadoc at face value
                    // - Accept LALT & RALT instead of LMENU & RMENU
                    keyBindings.add(Keyboard.getKeyIndex(
                            keyName.replace("KEY_", "").replace("ALT", "MENU")));
                }
                ShortcutType shortcutType = propNameToShortcutType(key);
                if (shortcutType != null) {
                    shortcuts.put(shortcutType, keyBindings);
                }
                
                // Register key status listener
                for (Integer keyCode : keyBindings) {
                    shortcutKeysStatus.put(keyCode, false);
                }
            }
            
        }
        
        // Add Minecraft's Up & Down bindings to the shortcuts
        int upKeyCode = getKeyBindingForwardKeyCode(),
            downKeyCode = getKeyBindingBackKeyCode();
        
        shortcuts.get(ShortcutType.MOVE_UP).add(upKeyCode);
        shortcuts.get(ShortcutType.MOVE_DOWN).add(downKeyCode);
        shortcutKeysStatus.put(upKeyCode, false);
        shortcutKeysStatus.put(downKeyCode, false);
        
        // Add hotbar shortcuts (1-9) mappings & listeners
        List<Integer> keyBindings = new LinkedList<Integer>();
        int[] hotbarKeys = {Keyboard.KEY_1, Keyboard.KEY_2, Keyboard.KEY_3, 
                Keyboard.KEY_4, Keyboard.KEY_5, Keyboard.KEY_6,
                Keyboard.KEY_7, Keyboard.KEY_8, Keyboard.KEY_9,
                Keyboard.KEY_NUMPAD1, Keyboard.KEY_NUMPAD2, Keyboard.KEY_NUMPAD3,
                Keyboard.KEY_NUMPAD4, Keyboard.KEY_NUMPAD5, Keyboard.KEY_NUMPAD6, 
                Keyboard.KEY_NUMPAD7, Keyboard.KEY_NUMPAD8, Keyboard.KEY_NUMPAD9};
        for (int i : hotbarKeys) {
            keyBindings.add(i);
            shortcutKeysStatus.put(i, false);
        }
        shortcuts.put(ShortcutType.MOVE_TO_SPECIFIC_HOTBAR_SLOT, keyBindings);
        
    }
    
    public Vector<Integer> getDownShortcutKeys() {
        updateKeyStatuses();
        Vector<Integer> downShortcutKeys = new Vector<Integer>();
        for (Integer key : shortcutKeysStatus.keySet()) {
            if (shortcutKeysStatus.get(key)) {
                downShortcutKeys.add(key);
            }
        }
        return downShortcutKeys;
    }
    
    public void handleShortcut(em guiContainer) {
        // IMPORTANT: This method is called before the default action is executed.
        
        updateKeyStatuses();
        
        // Initialization
        int ex = Mouse.getEventX(), ey = Mouse.getEventY();
        int x = (ex * getWidth(guiContainer)) / getDisplayWidth();
        int y = getHeight(guiContainer) - (ey * getHeight(guiContainer)) / getDisplayHeight() - 1;
        boolean shortcutValid = false;
        
        // Check that the slot is not empty
        sx slot = getSlotAtPosition(guiContainer, x, y);
        
        if (slot != null && hasStack(slot)) {
            
            // Choose shortcut type
            ShortcutType shortcutType = defaultAction;
            if (isActive(ShortcutType.MOVE_TO_SPECIFIC_HOTBAR_SLOT) != -1) {
                shortcutType = ShortcutType.MOVE_TO_SPECIFIC_HOTBAR_SLOT;
                shortcutValid = true;
            }
            if (isActive(ShortcutType.MOVE_ALL_ITEMS) != -1) {
                shortcutType = ShortcutType.MOVE_ALL_ITEMS;
                shortcutValid = true;
            }
            else if (isActive(ShortcutType.MOVE_ONE_ITEM) != -1) {
                shortcutType = ShortcutType.MOVE_ONE_ITEM;
                shortcutValid = true;
            }
            
            // Choose target section
            try {
                InvTweaksContainerManager container = new InvTweaksContainerManager(mc);
                InvTweaksContainerSection srcSection = container.getSlotSection(getSlotNumber(slot));
                InvTweaksContainerSection destSection = null;
                
                // Set up available sections
                Vector<InvTweaksContainerSection> availableSections = new Vector<InvTweaksContainerSection>();
                if (container.hasSection(InvTweaksContainerSection.CHEST)) {
                    availableSections.add(InvTweaksContainerSection.CHEST);
                }
                else if (container.hasSection(InvTweaksContainerSection.CRAFTING_IN)) {
                    availableSections.add(InvTweaksContainerSection.CRAFTING_IN);
                }
                else if (container.hasSection(InvTweaksContainerSection.FURNACE_IN)) {
                    availableSections.add(InvTweaksContainerSection.FURNACE_IN);
                }
                availableSections.add(InvTweaksContainerSection.INVENTORY_NOT_HOTBAR);
                availableSections.add(InvTweaksContainerSection.INVENTORY_HOTBAR);
                
                // Check for destination modifiers
                int destinationModifier = 0; 
                if (isActive(ShortcutType.MOVE_UP) != -1
                        || defaultDestination == ShortcutType.MOVE_UP) {
                    destinationModifier = -1;
                }
                else if (isActive(ShortcutType.MOVE_DOWN) != -1
                        || defaultDestination == ShortcutType.MOVE_DOWN) {
                    destinationModifier = 1;
                }
                
                if (destinationModifier == 0) {
                    // Default behavior
                    if (isGuiChest(guiContainer)) {
                        switch (srcSection) {
                        case CHEST:
                            destSection = InvTweaksContainerSection.INVENTORY; break;
                        default:
                            destSection = InvTweaksContainerSection.CHEST; break;
                        }
                    }
                    else {
                        switch (srcSection) {
                        case INVENTORY_HOTBAR:
                            destSection = InvTweaksContainerSection.INVENTORY_NOT_HOTBAR; break;
                        case CRAFTING_IN:
                        case FURNACE_IN:
                            destSection = InvTweaksContainerSection.INVENTORY_NOT_HOTBAR; break;
                        default:
                            destSection = InvTweaksContainerSection.INVENTORY_HOTBAR;
                        }
                    }
                }
                
                else {
                    // Specific destination
                    shortcutValid = true;
                    int srcSectionIndex = availableSections.indexOf(srcSection);
                    if (srcSectionIndex != -1) {
                        destSection = availableSections.get(
                                (availableSections.size() + srcSectionIndex + 
                                        destinationModifier) % availableSections.size());
                    }
                    else {
                        destSection = InvTweaksContainerSection.INVENTORY;
                    }
                }
                
                // Don't trigger the shortcut if we don't know on what we are clicking.
                if (srcSection == InvTweaksContainerSection.UNKNOWN) {
                    shortcutValid = false;
                }
                
                if (shortcutValid || isActive(ShortcutType.DROP) != -1) {
                    
                    initAction(getSlotNumber(slot), shortcutType, destSection);
                    
                    if (shortcutType == ShortcutType.MOVE_TO_SPECIFIC_HOTBAR_SLOT) {
                        
                        // Move to specific hotbar slot
                        String keyName = Keyboard.getKeyName(
                                isActive(ShortcutType.MOVE_TO_SPECIFIC_HOTBAR_SLOT));
                        int destIndex = -1+Integer.parseInt(keyName.replace("NUMPAD", ""));
                        container.move(fromSection, fromIndex,
                                InvTweaksContainerSection.INVENTORY_HOTBAR, destIndex);
                        
                    } else {
                        
                        // Drop or move
                        if (srcSection == InvTweaksContainerSection.CRAFTING_OUT) {
                            craftAll(Mouse.isButtonDown(1), isActive(ShortcutType.DROP) != -1);
                        } else {
                            move(Mouse.isButtonDown(1), isActive(ShortcutType.DROP) != -1);
                        }
                    }
                    
                    // Reset mouse status to prevent default action.
                    Mouse.destroy();
                    Mouse.create();
                    
                    // Fixes a tiny glitch (Steve looks for a short moment
                    // at [0, 0] because of the mouse reset).
                    Mouse.setCursorPosition(ex, ey);
                }
    
            } catch (Exception e) {
               InvTweaks.logInGameErrorStatic("Failed to trigger shortcut", e);
            }
        }
            
    }

    private void move(boolean separateStacks, boolean drop) throws Exception {
        
        int toIndex = -1;
        
        synchronized(this) {
    
            toIndex = getNextIndex(separateStacks, drop);
            if (toIndex != -1) {
                switch (shortcutType) {
                
                case MOVE_ONE_STACK:
                {
                    sx slot = container.getSlot(fromSection, fromIndex);
                    while (hasStack(slot) && toIndex != -1) {
                        container.move(fromSection, fromIndex, toSection, toIndex);
                        toIndex = getNextIndex(separateStacks, drop);
                    }
                    break;
    
                }
                
                case MOVE_ONE_ITEM:
                {
                    container.moveSome(fromSection, fromIndex, toSection, toIndex, 1);
                    break;
                }
                    
                case MOVE_ALL_ITEMS:
                {
                    for (sx slot : container.getSlots(fromSection)) {
                        if (hasStack(slot) && areSameItemType(fromStack, getStack(slot))) {
                            int fromIndex = container.getSlotIndex(getSlotNumber(slot));
                            while (hasStack(slot) && toIndex != -1 && 
                                    !(fromSection == toSection && fromIndex == toIndex)) {
                                boolean moveResult = container.move(fromSection, fromIndex,
                                        toSection, toIndex);
                                if (!moveResult) {
                                    break;
                                }
                                toIndex = getNextIndex(separateStacks, drop);
                            }
                        }
                    }
                }
                    
                }
            }
            
        }
    }
    
    private void craftAll(boolean separateStacks, boolean drop) throws Exception {
        int toIndex = getNextIndex(separateStacks, drop);
        sx slot = container.getSlot(fromSection, fromIndex);
        if (hasStack(slot) ) {
            // Store the first item type to craft, to make
            // sure it doesn't craft something else in the end
            int idToCraft = getItemID(getStack(slot));
            do {
                container.move(fromSection, fromIndex, toSection, toIndex);
                toIndex = getNextIndex(separateStacks, drop);
                if (getHoldStack() != null) {
                    container.leftClick(toSection, toIndex);
                    toIndex = getNextIndex(separateStacks, drop);
                }
            
            } while (hasStack(slot) 
                    && getItemID(getStack(slot)) == idToCraft
                    && toIndex != -1);
        }
    }

    /**
     * Checks if the Up/Down controls that are listened are outdated
     * @return true if the shortuts listeners have to be reset
     */
    private boolean haveControlsChanged() {
        return (!shortcutKeysStatus.containsKey(getKeyBindingForwardKeyCode())
                || !shortcutKeysStatus.containsKey(getKeyBindingBackKeyCode()));
    }

    private void updateKeyStatuses() {
        if (haveControlsChanged())
            reset();
        for (int keyCode : shortcutKeysStatus.keySet()) {
            if (Keyboard.isKeyDown(keyCode)) {
                if (!shortcutKeysStatus.get(keyCode)) {
                    shortcutKeysStatus.put(keyCode, true);
                }
            }
            else {
                shortcutKeysStatus.put(keyCode, false);
            }
        }
    }

    private int getNextIndex(boolean emptySlotOnly, boolean drop) {
        
        if (drop) {
            return DROP_SLOT;
        }
        
        int result = -1;

        // Try to merge with existing slot
        if (!emptySlotOnly) {
            int i = 0;
            for (sx slot : container.getSlots(toSection)) {
                if (hasStack(slot)) {
                    ul stack = getStack(slot);
                    if (areItemsEqual(stack, fromStack)
                            && getStackSize(stack) < getMaxStackSize(stack)) {
                        result = i;
                        break;
                    }
                }
                i++;
            }
        }
        
        // Else find empty slot
        if (result == -1) {
            result = container.getFirstEmptyIndex(toSection);
        }
        
        // Switch from FURNACE_IN to FURNACE_FUEL if the slot is taken
        if (result == -1 && toSection == InvTweaksContainerSection.FURNACE_IN) {
            toSection =  InvTweaksContainerSection.FURNACE_FUEL;
            result = container.getFirstEmptyIndex(toSection);
        }
        
        return result;
    }

    /**
     * @param shortcutType
     * @return The key that made the shortcut active
     */
    private int isActive(ShortcutType shortcutType) {
        for (Integer keyCode : shortcuts.get(shortcutType)) {
            if (shortcutKeysStatus.get(keyCode) && 
                    // AltGr also activates LCtrl, make sure the real LCtrl has been pressed
                    (keyCode != 29 || !Keyboard.isKeyDown(184))) {
                return keyCode;
            }
        }
        return -1;
    }

    private void initAction(int fromSlot, ShortcutType shortcutType, InvTweaksContainerSection destSection) throws Exception {
        
        // Set up context
        this.container = new InvTweaksContainerManager(mc);
        this.fromSection = container.getSlotSection(fromSlot);
        this.fromIndex = container.getSlotIndex(fromSlot);
        this.fromStack = container.getItemStack(fromSection, fromIndex);
        this.shortcutType = shortcutType;
        this.toSection = destSection;
        
        // Put hold stack down
        if (getHoldStack() != null) {
            
            container.leftClick(fromSection, fromIndex);
            
            // Sometimes (ex: crafting output) we can't put back the item
            // in the slot, in that case choose a new one.
            if (getHoldStack() != null) {
                int firstEmptyIndex = container.getFirstEmptyIndex(InvTweaksContainerSection.INVENTORY);
                if (firstEmptyIndex != -1) {
                   fromSection = InvTweaksContainerSection.INVENTORY;
                   fromSlot = firstEmptyIndex;
                   container.leftClick(fromSection, fromSlot);
                   
                }
                else {
                    throw new Exception("Couldn't put hold item down");
                }
            }
        }
    }
    
    private sx getSlotAtPosition(em guiContainer, int i, int j) { 
        // Copied from class 'em' (GuiContainer)
        for (int k = 0; k < getSlots(getContainer(guiContainer)).size(); k++) {
            sx localsx = (sx) getSlots(getContainer(guiContainer)).get(k);
            if (InvTweaks.getIsMouseOverSlot(guiContainer, localsx, i, j)) {
                return localsx;
            }
        }
        return null;
    }

    
    private ShortcutType propNameToShortcutType(String property) {
        if (property.equals(InvTweaksConfig.PROP_SHORTCUT_ALL_ITEMS)) {
            return ShortcutType.MOVE_ALL_ITEMS;
        } else if (property.equals(InvTweaksConfig.PROP_SHORTCUT_DOWN)) {
            return ShortcutType.MOVE_DOWN;
        } else if (property.equals(InvTweaksConfig.PROP_SHORTCUT_DROP)) {
            return ShortcutType.DROP;
        } else if (property.equals(InvTweaksConfig.PROP_SHORTCUT_ONE_ITEM)) {
            return ShortcutType.MOVE_ONE_ITEM;
        } else if (property.equals(InvTweaksConfig.PROP_SHORTCUT_ONE_STACK)) {
            return ShortcutType.MOVE_ONE_STACK;
        } else if (property.equals(InvTweaksConfig.PROP_SHORTCUT_UP)) {
            return ShortcutType.MOVE_UP;
        } else {
            return null;
        }
    }
    
}
