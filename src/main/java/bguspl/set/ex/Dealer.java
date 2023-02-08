package bguspl.set.ex;

import bguspl.set.Env;


import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {
    /**
     * final static constants.
     */
    private final int ISLEGAL = 0;
    private final int ISCORRECT = 1;
    public static final int ID = 0;
    public static final int SLOT = 1;
    public static final long BUFFER = 800;

    /**
     * Dealer thread
     */
    private Thread dealerThread;

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    /**
     * Default insignificant value, variable to be used for thread waiting time.
     */
    private long waitTime = 1000;



    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        //Create threads for each player
        for (Player player : players) {
            player.createThread();
        }
        //Start all threads representing players
        for (Player player : players) {
            player.runThread();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        //terminate all the players
        for (int i = players.length - 1; i >= 0; i--) {
            players[i].terminate();
        }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            //removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     * WAS NOT USED EVENTUALLY!! kept in code just in case.
     */
    private void removeCardsFromTable() {
        // Lock table
        try {
            table.tableChangeSema.acquire();
            // Obtain list of cards on table
            List<Integer> cardsOnTable = table.getCardsOnTable();
            table.tableChangeSema.release();
            // Check if there are sets on the table - if no sets, put all the cards back in the deck
            if (env.util.findSets(cardsOnTable, 1).isEmpty()) {
                // Move all cards from slot to deck
                removeAllCardsFromTable();
            }
        } catch (InterruptedException ignored) {table.tableChangeSema.release();}


        // Unlock table
    }

    /**
     * Test purposes only!!
     */
    public void testPlaceCards() {placeCardsOnTable();}

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     *
     */
    private void placeCardsOnTable() {
        try {
            table.tableChangeSema.acquire();
            if (deck.size() > 0) {
                int cSlot = 0;
                // Obtain empty slots on table
                List<Integer> emptySlots = new ArrayList<>();
                for (Integer card : table.slotToCard) {
                    if (card == null) {
                        emptySlots.add(cSlot);
                    }
                    cSlot++;
                }
                
                // Shuffle the slots and the deck for randomization
                Collections.shuffle(emptySlots);
                Collections.shuffle(deck);
                // Place random cards on empty slots
                while (emptySlots.size() > 0 && deck.size() > 0) {
                    table.placeCard(deck.remove(deck.size() - 1), emptySlots.remove(emptySlots.size() - 1));
                }
                
            }
            // Terminate if no more sets are available.
            else {
                terminate = env.util.findSets(table.getCardsOnTable(),1).size() == 0;
            }

            table.tableChangeSema.release();
        } catch (InterruptedException ignored) {table.tableChangeSema.release();}
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        int cPlayer = -1;

        try {
            // Calculate complimentary to next timer update for waiting.
            int divider = 1000;
            long timeLeft = reshuffleTime - System.currentTimeMillis();
            if (timeLeft <= env.config.turnTimeoutWarningMillis)  divider = 10;
            if (timeLeft % divider == 0) timeLeft += 1;
            waitTime = timeLeft % divider;
            if (table.playersQueueSema.tryAcquire(waitTime, TimeUnit.MILLISECONDS)) {
                if (!table.playersWithThreeTokens.isEmpty()) {
                    cPlayer = table.playersWithThreeTokens.peek();
                    // Making sure the player is indeed waiting for the dealer to check his set.
                    if(players[cPlayer].isWaiting)
                        cPlayer = table.playersWithThreeTokens.remove();
                    else
                        cPlayer = -1;
                    table.playersQueueSema.release();
                }
                else {
                    table.playersQueueSema.release();
                    synchronized (table.playersWithThreeTokens) {
                        timeLeft = reshuffleTime - System.currentTimeMillis();
                        if (timeLeft % divider == 0) timeLeft += 1;
                        table.playersWithThreeTokens.wait(timeLeft % divider);
                    }
                }
            }

        } catch (InterruptedException ignored) {table.playersQueueSema.release();}

        checkSets(cPlayer);
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        // TO DO
        long timeLeft = reshuffleTime - System.currentTimeMillis();
        if (timeLeft > 0) {
            reset = timeLeft <= env.config.turnTimeoutWarningMillis;
            if (reset)
                env.ui.setCountdown(timeLeft, reset);
            else
                env.ui.setCountdown(timeLeft + BUFFER, reset);
        }
        else
            env.ui.setCountdown(0, true);
    }

    /*
     * Test purposes only!!
     */
    public void removeCardsTest() {
        removeAllCardsFromTable();
    }

    /**
     * Moves all the cards on the table back to the deck.
     */
    private void removeAllCardsFromTable() {
        // Clear all players tokens
        try {
            table.tableChangeSema.acquire();
            for (Player cPlayer : players) {
                cPlayer.clearSet();
            }
            for (int i = 0; i < env.config.columns * env.config.rows; i++) {
                if (table.slotToCard[i] != null) {
                    deck.add(table.slotToCard[i]);
                    table.removeCard(i);
                }
            }
            table.tableChangeSema.release();
        } catch (InterruptedException ignored) {table.tableChangeSema.release();}
    }

    /**
     * @return How many cards are in the deck.
     */
    public int getDeckSize() {
        return deck.size();
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int winnersNum = 0;
        int winnerScore = Integer.MIN_VALUE;
        for (Player cPlayer : players) {
            if (cPlayer.score() > winnerScore) {
                winnersNum = 1;
                winnerScore = cPlayer.score();
            } else if (cPlayer.score() == winnerScore) {
                winnersNum++;
            }
        }
        int[] winners = new int[winnersNum];
        int index = 0;
        for (Player cPlayer : players) {
            if (cPlayer.score() == winnerScore) {
                winners[index] = cPlayer.id;
                index++;
            }
        }
        env.ui.announceWinner(winners);
    }

    /**
    * Returns whether the set of cPlayer is legal and correct.
    * answer[0] - is the set legal
    * answer[1] - is the set correct
     */
    private boolean[] getSetProperties(int cPlayer) {
        boolean[] answer = new boolean[2];
        answer[ISLEGAL] = true; // Is the set legal (no nulls)
        int[] cards = new int[env.config.featureSize];
        synchronized(players[cPlayer].tokenSlots) {
            int j = 0;
            List<Integer> tokensToRemoveBecauseNull = new LinkedList<Integer>();
            for (Integer nextSlot: players[cPlayer].tokenSlots){
                Integer nextCard = table.slotToCard[nextSlot] ;
                if (nextCard == null) {
                    answer[ISLEGAL] = false;
                    tokensToRemoveBecauseNull.add(nextSlot);
                }
                else cards[j] = nextCard;
                j++;
            }
            //Remove the slot pointing to null
            for (Integer slotToRemove: tokensToRemoveBecauseNull) {
                players[cPlayer].tokenSlots.remove(slotToRemove);
            }
        }
        answer[ISCORRECT] = env.util.testSet(cards); // Is the set correct
        return answer;
    }

    /**
    * Performs the actions needed when a player has a correct set
    * grants a point, removes overlapping tokens from other sets, removes cards from table
    * @param cPlayer - The player that submited a correct set.
     */
    private void correctSetActions(int cPlayer) throws InterruptedException {
        // Give score
        players[cPlayer].point();

        // Go over other players and collect tokens on removed slots.
        // [0] - player id
        // [1] - slot of token to be removed
        List<Integer[]> tokensToRemove = new ArrayList<Integer[]>();
        for (Player playerToCheck : players) {
            if (playerToCheck.id != cPlayer) {
                synchronized (playerToCheck.tokenSlots) {
                    playerToCheck.checkSimilarities(players[cPlayer].getTokenSlots(), tokensToRemove);
                }
            }
        }
        // Remove relevant tokens
        table.tableChangeSema.acquire();
        for (Integer[] cToken : tokensToRemove) {
            table.removeToken(cToken[ID], cToken[SLOT]);
        }
        // Remove relevant cards
        for (Integer slot : players[cPlayer].tokenSlots) {
            table.removeCard(slot);
        }
        table.tableChangeSema.release();

        // Update reshuffle time
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
    }

    /**
     * Checks the players' sets that are waiting for the dealer
     * @param cPlayer - first player to check his set
     */
    private void checkSets(int cPlayer) {
        try {
            while (cPlayer != -1) {
                // Get current player set to check

                boolean[] setProperties = getSetProperties(cPlayer);

                updateTimerDisplay(false);
                // check if set is legal size
                if (players[cPlayer].tokenSlots.size() == env.config.featureSize) {
                    // check if legal set
                    if (setProperties[ISLEGAL] && setProperties[ISCORRECT]) {
                        correctSetActions(cPlayer);
                        
                    }
                    // Set is legal (3 tokens on valid slots) but incorrect.
                    else if (setProperties[ISLEGAL] && !setProperties[ISCORRECT])
                        players[cPlayer].penalty();

                }
                
                updateTimerDisplay(false);

                // Notify current player to wake up
                synchronized(players[cPlayer].tokenSlots) {
                    players[cPlayer].tokenSlots.notifyAll();
                }

                // Get next player to check if available.
                if (table.playersQueueSema.tryAcquire()) {
                    if (!table.playersWithThreeTokens.isEmpty()) {
                        cPlayer = table.playersWithThreeTokens.peek();
                        // Making sure the player is indeed waiting for the dealer to check his set.
                        if(players[cPlayer].isWaiting)
                            cPlayer = table.playersWithThreeTokens.remove();
                        else
                            cPlayer = -1;
                    }
                    else cPlayer = -1;
                    table.playersQueueSema.release();
                }
                else
                    cPlayer = -1;
            }
        } catch (InterruptedException ignored) {
            table.playersQueueSema.release();
            table.tableChangeSema.release();
        }
    }
}
