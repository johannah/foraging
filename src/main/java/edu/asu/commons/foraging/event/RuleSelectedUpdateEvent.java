package edu.asu.commons.foraging.event;


import java.util.List;
import java.util.Map;

import edu.asu.commons.event.AbstractPersistableEvent;
import edu.asu.commons.foraging.model.GroupDataModel;
import edu.asu.commons.foraging.rules.Strategy;
import edu.asu.commons.net.Identifier;

/**
 * $Id$
 * 
 * Sent from a client to the server signaling that the client 
 * has updated the votes to the given options
 * 
 * @author <a href='allen.lee@asu.edu'>Allen Lee</a>
 * @version $Revision$
 */

public class RuleSelectedUpdateEvent extends AbstractPersistableEvent {

    private static final long serialVersionUID = 4360213814026474451L;
    private final List<Strategy> selectedStrategies;
    private final Map<Strategy, Integer> votingResults;
    private final GroupDataModel group;
  
    public RuleSelectedUpdateEvent(Identifier id, GroupDataModel group, List<Strategy> selectedStrategies, Map<Strategy, Integer> votingResults) {
        super(id, String.format("Strategies (first is tiebreaker): %s, All nominations: %s", selectedStrategies, votingResults));
        this.selectedStrategies = selectedStrategies;
        this.votingResults = votingResults;
        this.group = group;
    }
    
    public Strategy getSelectedRule() {
        return selectedStrategies.get(0);
    }
    
    public List<Strategy> getSelectedStrategies() {
        return selectedStrategies;
    }
    
    public Map<Strategy, Integer> getVotingResults() {
        return votingResults;
    }

    /**
     * @return the group
     */
    public GroupDataModel getGroup() {
        return group;
    }


}
