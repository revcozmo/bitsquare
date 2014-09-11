/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.gui.main.trade.orderbook;

import io.bitsquare.arbitrator.Arbitrator;
import io.bitsquare.bank.BankAccount;
import io.bitsquare.locale.Country;
import io.bitsquare.locale.CurrencyUtil;
import io.bitsquare.msg.MessageFacade;
import io.bitsquare.msg.listeners.OrderBookListener;
import io.bitsquare.settings.Settings;
import io.bitsquare.trade.Direction;
import io.bitsquare.trade.Offer;
import io.bitsquare.trade.TradeManager;
import io.bitsquare.user.User;

import java.io.IOException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

import net.tomp2p.peers.Number640;
import net.tomp2p.storage.Data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
TODO move to OrderBookModel when converted to new UI structure
remove dependencies to tomp2p
import net.tomp2p.peers.Number160;
import net.tomp2p.storage.Data;
 */

public class OrderBook implements OrderBookListener {
    private static final Logger log = LoggerFactory.getLogger(OrderBook.class);
    private final ObservableList<OrderBookListItem> allOffers = FXCollections.observableArrayList();
    private final FilteredList<OrderBookListItem> filteredList = new FilteredList<>(allOffers);
    // FilteredList does not support sorting, so we need to wrap it to a SortedList
    private final SortedList<OrderBookListItem> offerList = new SortedList<>(filteredList);
    private final Settings settings;
    private final User user;
    private final MessageFacade messageFacade;
    private final TradeManager tradeManager;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public OrderBook(Settings settings, User user, MessageFacade messageFacade, TradeManager tradeManager) {
        this.settings = settings;
        this.user = user;
        this.messageFacade = messageFacade;
        this.tradeManager = tradeManager;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void init() {
        messageFacade.addOrderBookListener(this);
    }

    public void cleanup() {
        messageFacade.removeOrderBookListener(this);
    }

    public void loadOffers() {
        if (user.getCurrentBankAccount() != null) {
            messageFacade.getOffers(user.getCurrentBankAccount().getCurrency().getCurrencyCode());
        }
        else {
            messageFacade.getOffers(CurrencyUtil.getDefaultCurrency().getCurrencyCode());
        }
    }

    public void removeOffer(Offer offer) {
        tradeManager.removeOffer(offer);
    }

    public void applyFilter(OrderBookFilter orderBookFilter) {
        filteredList.setPredicate(orderBookListItem -> {
            Offer offer = orderBookListItem.getOffer();
            BankAccount currentBankAccount = user.getCurrentBankAccount();

            if (orderBookFilter == null || currentBankAccount == null || orderBookFilter.getDirection() == null) {
                return false;
            }

            // The users current bank account currency must match the offer currency (1 to 1)
            boolean currencyResult = currentBankAccount.getCurrency().equals(offer.getCurrency());

            // The offer bank account country must match one of the accepted countries defined in the settings (1 to n)
            boolean countryResult = countryInList(offer.getBankAccountCountry(), settings.getAcceptedCountries());

            // One of the supported languages from the settings must match one of the offer languages (n to n)
            boolean languageResult =
                    languagesInList(settings.getAcceptedLanguageLocales(), offer.getAcceptedLanguageLocales());

            // Apply applyFilter only if there is a valid value set
            // The requested amount must be lower or equal then the offer amount
            boolean amountResult = true;
            if (orderBookFilter.getAmount() != null)
                amountResult = orderBookFilter.getAmount().compareTo(offer.getAmount()) <= 0;

            // The requested trade direction must be opposite of the offerList trade direction
            boolean directionResult = !orderBookFilter.getDirection().equals(offer.getDirection());

            // Apply applyFilter only if there is a valid value set
            boolean priceResult = true;
            if (orderBookFilter.getPrice() != null) {
                if (offer.getDirection() == Direction.SELL)
                    priceResult = orderBookFilter.getPrice().compareTo(offer.getPrice()) >= 0;
                else
                    priceResult = orderBookFilter.getPrice().compareTo(offer.getPrice()) <= 0;
            }

            // The arbitrator defined in the offer must match one of the accepted arbitrators defined in the settings
            // (1 to n)
            boolean arbitratorResult = arbitratorsInList(offer.getArbitrators(), settings.getAcceptedArbitrators());

            boolean result = currencyResult && countryResult && languageResult && amountResult && directionResult &&
                    priceResult && arbitratorResult;

            return result;
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Interface implementation: MessageListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onOfferAdded(Data offerData, boolean success) {
        try {
            Object offerDataObject = offerData.object();
            if (offerDataObject instanceof Offer) {
                Offer offer = (Offer) offerDataObject;
                allOffers.add(new OrderBookListItem(offer));
            }
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    @Override
    public void onOffersReceived(Map<Number640, Data> dataMap, boolean success) {
        if (success && dataMap != null) {
            allOffers.clear();

            for (Data offerData : dataMap.values()) {
                try {
                    Object offerDataObject = offerData.object();
                    if (offerDataObject instanceof Offer) {
                        Offer offer = (Offer) offerDataObject;
                        OrderBookListItem orderBookListItem = new OrderBookListItem(offer);
                        allOffers.add(orderBookListItem);
                    }
                } catch (ClassNotFoundException | IOException e) {
                    e.printStackTrace();
                }
            }
        }
        else {
            allOffers.clear();
        }
    }

    @Override
    public void onOfferRemoved(Data offerData, boolean success) {
        if (success && offerData != null) {
            try {
                Object offerDataObject = offerData.object();
                if (offerDataObject instanceof Offer) {
                    Offer offer = (Offer) offerDataObject;
                    allOffers.removeIf(orderBookListItem -> orderBookListItem.getOffer().getId().equals(offer.getId()));
                }
            } catch (ClassNotFoundException | IOException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }
        else {
            log.warn("onOfferRemoved failed");
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getter
    ///////////////////////////////////////////////////////////////////////////////////////////


    public SortedList<OrderBookListItem> getOfferList() {
        return offerList;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean countryInList(Country countryToMatch, List<Country> list) {
        for (Country country : list) {
            if (country.getCode().equals(countryToMatch.getCode())) {
                return true;
            }
        }
        return false;
    }

    private boolean languagesInList(List<Locale> list1, List<Locale> list2) {
        for (Locale locale1 : list2) {
            for (Locale locale2 : list1) {
                if (locale1.getLanguage().equals(locale2.getLanguage())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean arbitratorsInList(List<Arbitrator> list1, List<Arbitrator> list2) {
        for (Arbitrator arbitrator1 : list2) {
            for (Arbitrator arbitrator2 : list1) {
                if (arbitrator1.getId().equals(arbitrator2.getId())) {
                    return true;
                }
            }
        }
        return false;
    }


}