package me.tianhangj.quickenchant.predicate;

import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User-facing enchantment request, mirroring clientcommands' ItemAndEnchantmentsPredicateArgument
 * semantics: rows of "with (enchantment, exact level)" / "without (enchantment)" plus a global
 * "exactly" toggle meaning the final enchantment set must contain nothing else.
 */
public class EnchantPredicate {
    public enum Mode {
        WITH,
        WITHOUT
    }

    public record Row(Mode mode, Holder<Enchantment> enchantment, int level) {
        /** level value meaning "any level" for WITH rows */
        public static final int ANY_LEVEL = 0;
    }

    private final List<Row> rows = new ArrayList<>();
    private boolean exactly = false;

    public List<Row> getRows() {
        return rows;
    }

    public boolean isExactly() {
        return exactly;
    }

    public void setExactly(boolean exactly) {
        this.exactly = exactly;
    }

    public void addRow(Row row) {
        rows.add(row);
    }

    public void removeRow(int index) {
        rows.remove(index);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Test the union of enchantment instances the automation will end up applying.
     * A WITH row with {@code level == ANY_LEVEL} matches the enchantment at any level.
     */
    public boolean test(List<EnchantmentInstance> enchantments) {
        for (Row row : rows) {
            int present = levelOf(enchantments, row.enchantment());
            if (row.mode() == Mode.WITH) {
                if (row.level() == Row.ANY_LEVEL ? present <= 0 : present != row.level()) {
                    return false;
                }
            } else {
                if (present > 0) {
                    return false;
                }
            }
        }
        if (exactly) {
            Set<Holder<Enchantment>> expected = new HashSet<>();
            for (Row row : rows) {
                if (row.mode() == Mode.WITH) {
                    expected.add(row.enchantment());
                }
            }
            for (EnchantmentInstance instance : enchantments) {
                if (!expected.remove(instance.enchantment())) {
                    return false;
                }
            }
            if (!expected.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static int levelOf(List<EnchantmentInstance> enchantments, Holder<Enchantment> enchantment) {
        for (EnchantmentInstance instance : enchantments) {
            if (instance.enchantment() == enchantment) {
                return instance.level();
            }
        }
        return 0;
    }
}
