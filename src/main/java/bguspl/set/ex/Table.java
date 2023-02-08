package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * players whose set is ready to be checked
     */
    public Queue<Integer> playersWithThreeTokens;

    /**
     * Semaphore for accessing players' set queue
     */
    public Semaphore playersQueueSema;

    /**
     * Semaphore for threads accesssing table.
     */
    public Semaphore tableChangeSema;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        playersWithThreeTokens = new ConcurrentLinkedQueue<Integer>();
        playersQueueSema = new Semaphore(1, true);
        tableChangeSema = new Semaphore(1, true);

    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     *
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
            cardToSlot[card] = slot;
            slotToCard[slot] = card;
            env.ui.placeCard(card, slot);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Removes a card from a grid slot on the table.
     *
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
            // Lock Table
            int card = slotToCard[slot];
            slotToCard[slot] = null;
            cardToSlot[card] = null;
            env.ui.removeCard(slot);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Places a player token on a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {   
        if (slotToCard[slot] != null)
            env.ui.placeToken(player, slot);
            
    }

    /**
     * Removes a token of a player from a grid slot.
     *
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        boolean removed = slotToCard[slot] != null;
        env.ui.removeToken(player, slot);
        return removed;
    }

    /**
     * Adds player's id to a queue of players waiting to be checked.
     * @param id - player id whose ready for the dealer to check his set.
     */
    public void placedThreeTokens(int id) {
        playersWithThreeTokens.add(id);
    }

    /**
     *
     * @return Queue of player id's that are waiting for the dealer to check their set
     */
    public Queue<Integer> getPlayersToCheck() {
        return playersWithThreeTokens;
    }

    /**
     *
     * @return List of cards that are on the table
     */
    public List<Integer> getCardsOnTable() {
        List<Integer> cards = new ArrayList<>();
            for (Integer cCard : slotToCard) {
                if (cCard != null)
                    cards.add(cCard);
            }
        return cards;
    }
}
