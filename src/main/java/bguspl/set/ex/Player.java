package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    /**
     * The current slots contains the players tokens
     */
    public Set<Integer> tokenSlots;


    /**
     * Queue of incoming key presses
     */

    private BlockingQueue<Integer> incomingKeysQueue;

    /**
     * From this time the player is allowed to act
     */

    private long timeToAct;

    /**
     * Whether the player needs to reset his tokens
     */
    private boolean clearSet = false;

    /**
     * True if needs to grant point, False if penalty, Null otherwise
     */

    private Boolean pointOrPenalty;

    /**
     * Whether the set needs to be sent for check.
     */
    private boolean setToCheck = false;

    /*
     * Whether the player is waiting for the dealer to check his set.
     */
    public boolean isWaiting = false;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        tokenSlots = new LinkedHashSet<>();
        timeToAct = System.currentTimeMillis();
        incomingKeysQueue = new ArrayBlockingQueue<Integer>(env.config.featureSize, true);

    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        if (!human) runAiThread();

        while (!terminate) {
            // Sleep until woken or needed (waits for input or dealer)
            sleepUntilNeeded();
            // Handle point or penalty
            handlePointPenalty();
            // construct set from queue
            processKeysPressed();
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an aiThread if needed only
     */
    public void createAiThread() {
        if (!human){
            createArtificialIntelligence();
            env.logger.info("Thread " + aiThread.getName() + " created.");
        }
    }

    public void runAiThread() {
        if (aiThread != null) aiThread.start();
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                int generatedKeyPress = (int)(Math.random() * env.config.tableSize);
                keyPressed(generatedKeyPress);
            }
            env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        timeToAct = System.currentTimeMillis();
        try {
            // Interrupt so playerThread will release all monitors
            playerThread.interrupt();
            playerThread.join();
        } catch (InterruptedException e) {
            env.logger.info("Thread " + playerThread.getName() + " didn't close nicely, trying again ");
            terminate();
        }

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        //env.logger.info("Thread " + Thread.currentThread().getName() + " entered keyPressed func with slot: " + slot);
        if (System.currentTimeMillis() > timeToAct) {
            // Insert key pressed to incoming queue
            try {
                incomingKeysQueue.put(slot);
                //env.logger.info("Thread " + Thread.currentThread().getName() + " inserted slot incomingKeysQueue  ");
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Takes a key pressed by the player and acts accordingly (adds token, removes token, etc..)
     */
    private void processKeysPressed() {
        // Get keyPressed
        int slotPressed = -1;
        try {
            slotPressed = incomingKeysQueue.take();
        } catch (InterruptedException e) {}

        if (slotPressed != -1) {
            Boolean action = null; //True - add token. False - remove token. null - none
            synchronized (tokenSlots) {

                // Remove token if already pressed
                if (tokenSlots.contains(slotPressed)) {
                    tokenSlots.remove(slotPressed);
                    action = false;
                    //env.logger.info("Thread " + Thread.currentThread().getName() + " removed token from " + slotPressed);
                }

                // Add token if needed
                else if (tokenSlots.size() < env.config.featureSize){
                    tokenSlots.add(slotPressed);
                    setToCheck = (tokenSlots.size() == env.config.featureSize);
                    action = true;
                    //env.logger.info("Thread " + Thread.currentThread().getName() + " placed token on " + slotPressed);
                }
            }

            // Perform table action
            try {
                table.tableChangeSema.acquire();
                if (action != null) {
                    if (action) table.placeToken(id, slotPressed);
                    else table.removeToken(id, slotPressed);
                }
                table.tableChangeSema.release();
            } catch (InterruptedException ignored) {table.tableChangeSema.release();}
        }
    }

    /**
     * Puts the thread to sleep until the dealer checks his set (if necessary)
     */
    private void sleepUntilNeeded() {
        if (setToCheck && tokenSlots.size() == env.config.featureSize) {
            // Tell dealer to check my set and notify him
            try {
                table.playersQueueSema.acquire();
                table.placedThreeTokens(id);
                table.playersQueueSema.release();
                synchronized (table.playersWithThreeTokens) {
                    table.playersWithThreeTokens.notifyAll();
                }

            } catch (InterruptedException ignored) {}
            // Wait until dealer checks my set
            synchronized (tokenSlots) {
                try {
                    isWaiting = true;
                    tokenSlots.wait();
                    isWaiting = false;
                } catch (InterruptedException ignored) {isWaiting = false;}
            }
        }
    }

    /**
     * Grants a point or penalty according to dealer decision and sleep until freeze time is over.
     */
    private void handlePointPenalty(){
        // Check if null - if so do nothing
        if (pointOrPenalty != null) {
            // if True - clear set
            if (pointOrPenalty) {
                clearSet();
            }
            // clear incomingkeys queue
            incomingKeysQueue.clear();
            // set pointPenalty to null
            pointOrPenalty = null;
            // Set no sets from this player for the dealer to check
            setToCheck = false;
            // Sleep for freeze time duration
            while (System.currentTimeMillis() < timeToAct) {
                try {
                    env.ui.setFreeze(id, (timeToAct - System.currentTimeMillis()));
                    Thread.sleep((timeToAct - System.currentTimeMillis()) % 1000);
                } catch (InterruptedException | IllegalArgumentException ignored) {
                }
            }
            env.ui.setFreeze(id, -1);
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        
        // Freeze the player for env.config.pointFreezeMillis
        timeToAct = System.currentTimeMillis() + env.config.pointFreezeMillis;
        try {
            synchronized (pointOrPenalty) {
                pointOrPenalty = true;
            }
        } catch (NullPointerException e) {pointOrPenalty = true;}
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        score++;
        env.ui.setScore(id, score);

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        
        // Freeze the player for env.config.penaltyFreezeMillis
        timeToAct = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
        try {
            synchronized (pointOrPenalty) {
                pointOrPenalty = false;
            }
        } catch (NullPointerException e) {pointOrPenalty = false;}
        
        /* Used to easily detect deadlocks.
        score--;
        env.ui.setScore(id, score);
        */
    }

    /**
     *
     * @return the player's score
     */
    public int score() {
        return score;
    }

    /**
     * @return The thread the current player associates with.
     */
    public Thread createThread() {
        playerThread =  new Thread(this, "Player" + id);
        env.logger.info("Thread " + playerThread.getName() + " created.");
        createAiThread();
        return playerThread;
    }

    /**
     * Runs the thread associated with this player.
     */
    public void runThread() {
        playerThread.start();
    }

    /**
     * @return The player's slots with tokens on them.
     */
    public Set<Integer> getTokenSlots() {
        return tokenSlots;
    }

    /**
     * Removes the player's tokens from the table and the set
     */
    public void clearSet() {
        synchronized (tokenSlots) {
        for (Integer slot : tokenSlots) {
            table.removeToken(id, slot);
        }
            tokenSlots.clear();
        }
    }

    /**
     *
     * @param otherSlots - Slots that are going to be empty and need to be cleared of tokens.
     * @param tokensToRemove - A list of tokens to be removed from the board.
     */
    public void checkSimilarities(Set<Integer> otherSlots, List<Integer[]> tokensToRemove) {

        for (Integer slotToCheck : otherSlots) {
            if (tokenSlots.contains(slotToCheck)) {
                tokenSlots.remove(slotToCheck);
                Integer[] cToken = new Integer[2];
                cToken[Dealer.ID] = id;
                cToken[Dealer.SLOT] = slotToCheck;
                tokensToRemove.add(cToken);
            }
        }
    }

    /**
     * For testing purposes only!!
     */
    public void enterToken(int slot) {
        tokenSlots.add(slot);
    }

}
