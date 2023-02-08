package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

import javax.swing.plaf.basic.BasicScrollPaneUI;
import javax.swing.text.TableView.TableRow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.booleanThat;


class DealerTest {

    private Table table;
    private Env env;
    private Player[] players;
    Dealer dealer;

    static class MockUtil implements Util {
        @Override
        public int[] cardToFeatures(int card) {
            return new int[0];
        }

        @Override
        public int[][] cardsToFeatures(int[] cards) {
            return new int[0][];
        }

        @Override
        public boolean testSet(int[] cards) {
            return false;
        }

        @Override
        public List<int[]> findSets(List<Integer> deck, int count) {
            return null;
        }

        @Override
        public void spin() {}
    }

    static class MockLogger extends Logger {
        protected MockLogger() {
            super("", null);
        }
    }

    static class MockUserInterface implements UserInterface {
        @Override
        public void dispose() {}
        @Override
        public void placeCard(int card, int slot) {}
        @Override
        public void removeCard(int slot) {}
        @Override
        public void setCountdown(long millies, boolean warn) {}
        @Override
        public void setElapsed(long millies) {}
        @Override
        public void setScore(int player, int score) {}
        @Override
        public void setFreeze(int player, long millies) {}
        @Override
        public void placeToken(int player, int slot) {}
        @Override
        public void removeTokens() {}
        @Override
        public void removeTokens(int slot) {}
        @Override
        public void removeToken(int player, int slot) {}
        @Override
        public void announceWinner(int[] players) {}
    };

    

    @BeforeEach
    void setUp() {
        Properties properties = new Properties();
        properties.put("Rows", "2");
        properties.put("Columns", "2");
        properties.put("FeatureSize", "3");
        properties.put("FeatureCount", "4");
        properties.put("TableDelaySeconds", "0");
        properties.put("PlayerKeys1", "81,87,69,82");
        properties.put("PlayerKeys2", "85,73,79,80");
        properties.put("ComputerPlayers", "2");
        MockLogger logger = new MockLogger();
        Config config = new Config(logger, properties);
        Integer[] slotToCard = new Integer[config.tableSize];
        Integer[] cardToSlot = new Integer[config.deckSize];
        players = new Player[config.players];

        

        env = new Env(logger, config, new MockUserInterface(), new MockUtil());
        table = new Table(env, slotToCard, cardToSlot);
        dealer = new Dealer(env, table, players);
        for (int i = 0; i < players.length; i++)
            players[i] = new Player(env, dealer, table, i, i < env.config.humanPlayers);
    }

    private int fillSomeSlots() {
        table.slotToCard[1] = 3;
        table.slotToCard[2] = 5;
        table.cardToSlot[3] = 1;
        table.cardToSlot[5] = 2;

        return 2;
    }

    private void fillSomeTokens(int tokensNumber) {
        for (Player cPlayer : players) {
            for (int i = 0; i <= tokensNumber; i++) {
                cPlayer.enterToken((int)(Math.random() * env.config.tableSize));
            }
        }
    }


    @Test
    void placeCardsOnTable() {
        int firstSize = dealer.getDeckSize();

        dealer.testPlaceCards();

        int newSize = table.getCardsOnTable().size() + dealer.getDeckSize();
        assertEquals(newSize, firstSize);

    }

    @Test
    void removeAllCardsFromTable() {
        fillSomeSlots();

        int firstDeckSize = dealer.getDeckSize();
        int cardsOnTable = table.getCardsOnTable().size();
        
        fillSomeTokens(2);
        dealer.removeCardsTest();
        

        int lastDeckSize = dealer.getDeckSize();
        int newCardsOnTable = table.getCardsOnTable().size();

        assertEquals(firstDeckSize + cardsOnTable, lastDeckSize);
        assertEquals(newCardsOnTable, 0);
        for(Player cPlayer : players) {
            assertEquals(cPlayer.tokenSlots.size(), 0);
        }
    }

}
