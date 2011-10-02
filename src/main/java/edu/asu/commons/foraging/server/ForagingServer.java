package edu.asu.commons.foraging.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import edu.asu.commons.command.Command;
import edu.asu.commons.event.BeginRoundRequest;
import edu.asu.commons.event.ChatEvent;
import edu.asu.commons.event.ChatRequest;
import edu.asu.commons.event.ClientMessageEvent;
import edu.asu.commons.event.EndRoundRequest;
import edu.asu.commons.event.EventTypeProcessor;
import edu.asu.commons.event.FacilitatorMessageEvent;
import edu.asu.commons.event.FacilitatorRegistrationRequest;
import edu.asu.commons.event.RoundStartedMarkerEvent;
import edu.asu.commons.event.SetConfigurationEvent;
import edu.asu.commons.event.SocketIdentifierUpdateRequest;
import edu.asu.commons.experiment.AbstractExperiment;
import edu.asu.commons.experiment.StateMachine;
import edu.asu.commons.foraging.conf.RoundConfiguration;
import edu.asu.commons.foraging.conf.ServerConfiguration;
import edu.asu.commons.foraging.data.ForagingSaveFileConverter;
import edu.asu.commons.foraging.event.AgentInfoRequest;
import edu.asu.commons.foraging.event.BeginChatRoundRequest;
import edu.asu.commons.foraging.event.ClientMovementRequest;
import edu.asu.commons.foraging.event.ClientPoseUpdate;
import edu.asu.commons.foraging.event.ClientPositionUpdateEvent;
import edu.asu.commons.foraging.event.CollectTokenRequest;
import edu.asu.commons.foraging.event.EndRoundEvent;
import edu.asu.commons.foraging.event.ExplicitCollectionModeRequest;
import edu.asu.commons.foraging.event.FacilitatorCensoredChatRequest;
import edu.asu.commons.foraging.event.FacilitatorEndRoundEvent;
import edu.asu.commons.foraging.event.FacilitatorSanctionUpdateEvent;
import edu.asu.commons.foraging.event.FacilitatorUpdateEvent;
import edu.asu.commons.foraging.event.HarvestFruitRequest;
import edu.asu.commons.foraging.event.HarvestResourceRequest;
import edu.asu.commons.foraging.event.LockResourceEvent;
import edu.asu.commons.foraging.event.LockResourceRequest;
import edu.asu.commons.foraging.event.PostRoundSanctionRequest;
import edu.asu.commons.foraging.event.PostRoundSanctionUpdateEvent;
import edu.asu.commons.foraging.event.QuizCompletedEvent;
import edu.asu.commons.foraging.event.QuizResponseEvent;
import edu.asu.commons.foraging.event.RealTimeSanctionRequest;
import edu.asu.commons.foraging.event.ResetTokenDistributionRequest;
import edu.asu.commons.foraging.event.RoundStartedEvent;
import edu.asu.commons.foraging.event.RuleSelectedUpdateEvent;
import edu.asu.commons.foraging.event.RuleVoteRequest;
import edu.asu.commons.foraging.event.SanctionAppliedEvent;
import edu.asu.commons.foraging.event.ShowRequest;
import edu.asu.commons.foraging.event.SurveyIdSubmissionRequest;
import edu.asu.commons.foraging.event.SynchronizeClientEvent;
import edu.asu.commons.foraging.event.TrustGameSubmissionEvent;
import edu.asu.commons.foraging.event.TrustGameSubmissionRequest;
import edu.asu.commons.foraging.event.UnlockResourceRequest;
import edu.asu.commons.foraging.model.ClientData;
import edu.asu.commons.foraging.model.Direction;
import edu.asu.commons.foraging.model.EnforcementMechanism;
import edu.asu.commons.foraging.model.GroupDataModel;
import edu.asu.commons.foraging.model.ResourceDispenser;
import edu.asu.commons.foraging.model.SanctionMechanism;
import edu.asu.commons.foraging.model.ServerDataModel;
import edu.asu.commons.foraging.rules.ForagingRule;
import edu.asu.commons.foraging.ui.Circle;
import edu.asu.commons.net.Dispatcher;
import edu.asu.commons.net.Identifier;
import edu.asu.commons.net.SocketIdentifier;
import edu.asu.commons.net.event.ConnectionEvent;
import edu.asu.commons.net.event.DisconnectionRequest;
import edu.asu.commons.util.Duration;
import edu.asu.commons.util.Utils;

/**
 * $Id: ForagingServer.java 529 2010-08-17 00:08:01Z alllee $
 * 
 * Main experiment server class for costly sanctioning 2D experiment.
 * 
 * @author <a href='mailto:Allen.Lee@asu.edu'>Allen Lee</a>
 * @version $Revision: 529 $
 */
public class ForagingServer extends AbstractExperiment<ServerConfiguration, RoundConfiguration> {

    private final Logger logger = Logger.getLogger(getClass().getName());

    private final Map<Identifier, ClientData> clients = new HashMap<Identifier, ClientData>();

    public final static int SYNCHRONIZATION_FREQUENCY = 60;
    public final static int SERVER_SLEEP_INTERVAL = 100;

    private Identifier facilitatorId;

    // FIXME: use java.util.concurrent constructs instead? CountDownLatch / CyclicBarrier?
    private final Object roundSignal = new Object();
    private final Object quizSignal = new Object();
    private final Object postRoundSanctioningSignal = new Object();
    private final Object agentDesignSignal = new Object();
    // FIXME: these latches don't quite do what we want. We need a way to reset them at each round.
    // private CountDownLatch postRoundSanctionLatch;

    private StateMachine stateMachine = new ForagingStateMachine();

    private ForagingPersister persister;

    private volatile int numberOfSubmittedQuizzes;
    private volatile int numberOfCompletedSanctions;
    private volatile int numberOfCompletedAgentDesigns;

    private int monitorRotationInterval;

    private Duration currentRoundDuration;
    // private Duration chatDuration;

    private volatile boolean experimentStarted;

    // FIXME: add the ability to reconfigure an already instantiated server
    public ForagingServer() {
        this(new ServerConfiguration());
    }

    public ForagingServer(ServerConfiguration configuration) {
        setConfiguration(configuration);
        persister = new ForagingPersister(getEventChannel(), configuration);
        try {
            Handler logHandler = new FileHandler(configuration.getLogFileDestination(), true);
            logHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(logHandler);
        } catch (IOException e) {
            e.printStackTrace();
            logger.severe("Unable to log to file : " + configuration.getLogFileDestination());
        }
    }

    @Override
    public void processReplInput(String input, BufferedReader reader) {
        if (input.equals("clients")) {
            System.out.println("Connected Clients: " + clients.size());
            for (Identifier id : clients.keySet()) {
                getLogger().info("\t" + id);
            }
        }
        else if (input.equals("skip")) {
            System.out.println("Notifying all signals.");
            Utils.notify(quizSignal);
            Utils.notify(roundSignal);
            Utils.notify(postRoundSanctioningSignal);
        }
        else if (input.equals("skip-quiz")) {
            System.out.println("skipping quiz");
            Utils.notify(quizSignal);
        }
        else if (input.equals("start-round")) {
            System.out.println("starting round");
            Utils.notify(roundSignal);
        }
        else if (input.equals("skip-post-round-sanction")) {
            System.out.println("Skipping post round sanctioning");
            Utils.notify(postRoundSanctioningSignal);
        }
        else if (input.equals("process-savefiles")) {
            System.out.print("Please enter the save directory path: ");
            try {
                String path = reader.readLine();
                boolean converted = ForagingSaveFileConverter.convert(path);
                if (!converted) {
                    System.out.println("Unable to convert from path: " + path);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected StateMachine getStateMachine() {
        return stateMachine;
    }
    
    private RoundConfiguration getCurrentRoundConfiguration() {
        return getConfiguration().getCurrentParameters();
    }

    enum ServerState {
        WAITING, ROUND_IN_PROGRESS
    };

    private class ForagingStateMachine implements StateMachine {
        private Command roundProcessor;
        private ServerDataModel serverDataModel;
        private ResourceDispenser resourceDispenser;
        private ServerState serverState;
        private final Duration secondTick = Duration.create(1000L);

        /**
         * Initializes the state machine before the experiment ever begins.
         */
        public void initialize() {
            serverState = ServerState.WAITING;
            serverDataModel = new ServerDataModel(getEventChannel());
            // these two seem to be paired up frequently.
            serverDataModel.setRoundConfiguration(getCurrentRoundConfiguration());
            initializeRoundProcessor();
            resourceDispenser = new ResourceDispenser(serverDataModel);
            initializeClientHandlers();
            initializeFacilitatorHandlers();
        }

        // FIXME: remove this as we phase out the 3d environment
        private void initializeRoundProcessor() {
            if (serverDataModel.getRoundConfiguration().is2dExperiment()) {
                roundProcessor = new Command() {
                    public void execute() {
                        processRound2d();
                    }
                };
            }
            else {
                roundProcessor = new Command() {
                    public void execute() {
                        processRound3d();
                    }
                };
            }
        }

        private void initializeClientHandlers() {
            addEventProcessor(new EventTypeProcessor<SocketIdentifierUpdateRequest>(SocketIdentifierUpdateRequest.class) {
                @Override
                public void handle(SocketIdentifierUpdateRequest request) {
                    SocketIdentifier socketId = request.getSocketIdentifier();
                    ClientData clientData = clients.get(socketId);
                    if (clientData == null) {
                        getLogger().warning("No client data available for socket: " + socketId);
                        return;
                    }
                    SocketIdentifier clientSocketId = (SocketIdentifier) clientData.getId();
                    clientSocketId.setStationNumber(request.getStationNumber());
                }
            });
            // client handlers
            addEventProcessor(new EventTypeProcessor<ConnectionEvent>(ConnectionEvent.class) {
                @Override
                public void handle(ConnectionEvent event) {
                    // handle incoming connections
                    if (experimentStarted) {
                        // do not allow any new connections
                        transmit(new ClientMessageEvent(event.getId(), "The experiment has already started, we cannot add you at this time."));
                        return;
                    }
                    Identifier identifier = event.getId();
                    synchronized (clients) {
                        clients.put(identifier, new ClientData(identifier));
                    }
                    // send welcome instructions and experiment configuration
                    transmit(new SetConfigurationEvent<RoundConfiguration>(identifier, getCurrentRoundConfiguration()));
                }
            });
            addEventProcessor(new EventTypeProcessor<DisconnectionRequest>(DisconnectionRequest.class) {
                @Override
                public void handle(DisconnectionRequest event) {
                    synchronized (clients) {
                        logger.warning("Disconnecting client, removing from clients: " + event.getId());
                        clients.remove(event.getId());
                    }
                }
            });
            addEventProcessor(new EventTypeProcessor<ChatRequest>(ChatRequest.class) {
                public void handle(final ChatRequest request) {
                    RoundConfiguration configuration = getCurrentRoundConfiguration();
                    if (!configuration.isChatEnabled()) {
                        sendFacilitatorMessage("configuration doesn't allow for chat but received " + request);
                        return;
                    }
                    if (configuration.isCensoredChat()) {
                        transmit(new FacilitatorCensoredChatRequest(facilitatorId, request));
                    }
                    else {
                        relayChatRequest(request);
                    }
                }
            });

            addEventProcessor(new EventTypeProcessor<QuizResponseEvent>(QuizResponseEvent.class) {
                public void handle(final QuizResponseEvent event) {
                    sendFacilitatorMessage("Received quiz response: " + event);
                    numberOfSubmittedQuizzes++;
                    transmit(new QuizCompletedEvent(facilitatorId, event));
                    ClientData clientData = clients.get(event.getId());
                    clientData.addCorrectQuizAnswers(event.getNumberOfCorrectAnswers());
                    if (numberOfSubmittedQuizzes >= clients.size()) {
                        // we're done, notify the quizSignal
                        sendFacilitatorMessage("Received all quizzes, ready to start round.");
                        Utils.notify(quizSignal);
                    }

                }
            });
            addEventProcessor(new EventTypeProcessor<PostRoundSanctionRequest>(PostRoundSanctionRequest.class) {
                public void handle(PostRoundSanctionRequest event) {
                    logger.info("Received post round sanction request");
                    clients.get(event.getId()).getGroupDataModel().handleSanctionRequest(event);
                    // postRoundSanctionLatch.countDown();
                    numberOfCompletedSanctions++;
                    if (numberOfCompletedSanctions == clients.size()) {
                        // send an updated debriefing to everyone again.
                        for (ClientData clientData : clients.values()) {
                            clientData.applyPostRoundSanctioning();
                        }
                        boolean lastRound = getConfiguration().isLastRound();
                        for (ClientData clientData : clients.values()) {
                            PostRoundSanctionUpdateEvent updateEvent = new PostRoundSanctionUpdateEvent(clientData, getCurrentRoundConfiguration(), lastRound);
                            transmit(updateEvent);
                        }
                        // update the facilitator
                        transmit(new FacilitatorSanctionUpdateEvent(facilitatorId, clients, lastRound));
                        Utils.notify(postRoundSanctioningSignal);
                        numberOfCompletedSanctions = 0;
                    }
                }
            });
            // 3d handlers
            addEventProcessor(new EventTypeProcessor<AgentInfoRequest>(AgentInfoRequest.class) {
                public void handle(AgentInfoRequest event) {
                    ClientData client = clients.get(event.getId());
                    client.setMale(event.isMale());
                    client.setHairColor(event.getHairColor());
                    client.setSkinColor(event.getSkinColor());
                    client.setShirtColor(event.getShirtColor());
                    client.setTrouserColor(event.getTrouserColor());
                    client.setShoesColor(event.getShoesColor());
                    numberOfCompletedAgentDesigns++;
                    if (numberOfCompletedAgentDesigns == clients.size()) {
                        // we're done, notify the sleeping queue.
                        Utils.notify(agentDesignSignal);
                    }
                }
            });

            addEventProcessor(new EventTypeProcessor<ClientMovementRequest>(ClientMovementRequest.class) {
                public void handle(ClientMovementRequest event) {
                    if (serverState == ServerState.WAITING)
                        return;
                    Identifier id = event.getId();
                    Direction direction = event.getDirection();
                    serverDataModel.moveClient(id, direction);
                }
            });
            addEventProcessor(new EventTypeProcessor<ExplicitCollectionModeRequest>(ExplicitCollectionModeRequest.class) {
                public void handleInExperimentThread(ExplicitCollectionModeRequest event) {
                    clients.get(event.getId()).setExplicitCollectionMode(event.isExplicitCollectionMode());
                }
            });
            addEventProcessor(new EventTypeProcessor<CollectTokenRequest>(CollectTokenRequest.class) {
                public void handle(CollectTokenRequest event) {
                    ClientData clientData = clients.get(event.getId());
                    // FIXME: change this to collect the token immediately
                    clientData.setCollecting();
                }
            });
            addEventProcessor(new EventTypeProcessor<ResetTokenDistributionRequest>(ResetTokenDistributionRequest.class) {
                public void handleInExperimentThread(ResetTokenDistributionRequest event) {
                    resourceDispenser.resetTokenDistribution(event);
                }
            });
            addEventProcessor(new EventTypeProcessor<RuleVoteRequest>(RuleVoteRequest.class) {
                int votesReceived = 0;
                @Override
                public void handle(RuleVoteRequest request) {
                    sendFacilitatorMessage("Received vote rule request: " + request);
                    ClientData client = clients.get(request.getId());
                    client.setVotedRule(request.getRule());
                    votesReceived++;
                    if (votesReceived >= clients.size()) {
                        // calculate votes
                        for (GroupDataModel group: serverDataModel.getGroups()) {
                            Map<ForagingRule, Integer> votingResults = group.generateVotingResults();
                            List<ForagingRule> selectedRules = group.getSelectedRules();
                            for (Identifier id: group.getClientIdentifiers()) {
                                sendFacilitatorMessage(String.format(
                                        "%s selected [%s] from all rules (%s)",
                                        group, selectedRules, votingResults));
                                transmit(new RuleSelectedUpdateEvent(id, selectedRules, votingResults));
                            }
                            
                        }
                    }


                }
            });
            addEventProcessor(new EventTypeProcessor<RealTimeSanctionRequest>(RealTimeSanctionRequest.class) {
                @Override
                public void handleInExperimentThread(final RealTimeSanctionRequest request) {
                    // validate request
                    // a user can sanction iff the following hold:
                    // 1. sanctioning is enabled or they are the monitor
                    // 2. the sanctioner has tokens
                    // 3. the resource distribution is non-empty
                    if (getCurrentRoundConfiguration().isVotingAndRegulationEnabled()) {
                        handleEnforcementSanctionRequest(request);
                    }
                    else {
                        handleRealTimeSanctionRequest(request);
                    }
                }
            });
            addEventProcessor(new EventTypeProcessor<ClientPoseUpdate>(ClientPoseUpdate.class) {
                public void handleInExperimentThread(ClientPoseUpdate event) {
                    if (serverState == ServerState.WAITING)
                        return;
                    ClientData client = clients.get(event.getId());
                    client.setPosition(event.getPosition());
                    client.setHeading(event.getHeading());
                    client.setAnimationState(event.getAnimationState());
                    client.setAnimationActiveFlag(event.isAnimationActive());
                }
            });
            // FIXME: turn into lockResource/unlockResource?
            addEventProcessor(new EventTypeProcessor<LockResourceRequest>(LockResourceRequest.class) {
                public void handleInExperimentThread(LockResourceRequest event) {
                    if (serverState == ServerState.WAITING)
                        return;
                    boolean successfullyLocked = serverDataModel.lockResource(event);
                    transmit(new LockResourceEvent(event.getId(), event.getResource(), successfullyLocked));
                }
            });
            addEventProcessor(new EventTypeProcessor<UnlockResourceRequest>(UnlockResourceRequest.class) {
                public void handleInExperimentThread(UnlockResourceRequest event) {
                    if (serverState == ServerState.WAITING)
                        return;
                    serverDataModel.unlockResource(event);
                }
            });
            addEventProcessor(new EventTypeProcessor<HarvestResourceRequest>(HarvestResourceRequest.class) {
                public void handleInExperimentThread(HarvestResourceRequest event) {
                    if (serverState == ServerState.WAITING)
                        return;
                    serverDataModel.harvestResource(event);
                }
            });
            addEventProcessor(new EventTypeProcessor<HarvestFruitRequest>(HarvestFruitRequest.class) {
                public void handleInExperimentThread(HarvestFruitRequest event) {
                    if (serverState == ServerState.WAITING)
                        return;
                    serverDataModel.harvestFruits(event);
                }
            });
        }

        private void handleEnforcementSanctionRequest(RealTimeSanctionRequest request) {
            ClientData sourceClient = clients.get(request.getSource());
            ClientData targetClient = clients.get(request.getTarget());

            GroupDataModel group = sourceClient.getGroupDataModel();
            if (!group.equals(targetClient.getGroupDataModel())) {
                logger.severe("source client and target client groups are different: " + sourceClient + targetClient);
                return;
            }
            EnforcementMechanism enforcementMechanism = group.getActiveEnforcementMechanism();
            switch (enforcementMechanism) {
                case EVERYONE_CAN_SANCTION:
                    handleRealTimeSanctionRequest(request);
                    break;
                case RANDOM_MONITOR:
                case ROTATING_MONITOR:
                    if (sourceClient.isMonitor()) {
                        // monitor can always sanction regardless of if they have enough tokens.
                        if (targetClient.getCurrentTokens() == 0) {
                            // nothing to take, so sanctioning has no effect.
                            transmit(new ClientMessageEvent(sourceClient.getId(),
                                    String.format("Ignoring token reduction request: # %d does not have any tokens to reduce.",
                                            targetClient.getAssignedNumber())));
                            return;
                        }
                        // monitors don't get any sanction costs.
                        targetClient.sanctionPenalty();
                        // add sanction request to the target client so they can figure out who just sanctioned them
                        sourceClient.getLatestSanctions().add(request);
                        targetClient.getLatestSanctions().add(request);
                        transmit(new ClientMessageEvent(sourceClient.getId(),
                                String.format("Subtracting %d tokens from # %d at the cost of 0 to yourself.",
                                        getCurrentRoundConfiguration().getSanctionPenalty(),
                                        targetClient.getAssignedNumber())));
                        transmit(new ClientMessageEvent(targetClient.getId(),
                                String.format("# %d subtracted %d tokens from you.", sourceClient.getAssignedNumber(), getCurrentRoundConfiguration()
                                        .getSanctionPenalty())));
                    }
                    break;
                case NONE:
                default:
                    logger.severe("tried to sanction with EnforcementMechanism.NONE");
            }
        }

        private void handleRealTimeSanctionRequest(RealTimeSanctionRequest request) {
            ClientData sourceClient = clients.get(request.getSource());
            ClientData targetClient = clients.get(request.getTarget());
            // validate request
            // FIXME:Added a new test condition to check for the simplified version of sanctioning
            boolean invalidSanctionRequest = sourceClient.getCurrentTokens() == 0 || targetClient.getCurrentTokens() == 0
                    || sourceClient.getGroupDataModel().isResourceDistributionEmpty()
                    || (sourceClient.getGroupDataModel().getActiveSanctionMechanism() == SanctionMechanism.NONE);
            if (invalidSanctionRequest) {
                // ignore the sanction request, send a message to the sanctioner.
                logger.warning("Ignoring token reduction request, sending new client error message event to : " + sourceClient.getId());
                if (sourceClient.getGroupDataModel().getActiveSanctionMechanism() == SanctionMechanism.NONE) {
                    transmit(new ClientMessageEvent(sourceClient.getId(),
                            String.format("Ignoring token reduction request: Sanctioning not allowed in this round", targetClient.getAssignedNumber())));
                }
                else {
                    transmit(new ClientMessageEvent(sourceClient.getId(),
                            String.format("Ignoring token reduction request: # %d does not have any tokens to reduce.", targetClient.getAssignedNumber())));
                }
                return;
            }
            sourceClient.sanctionCost();
            int sanctionCost = getCurrentRoundConfiguration().getSanctionCost();
            int subtractedTokens = targetClient.sanctionPenalty();
            // generate sanction applied event
            SanctionAppliedEvent sanctionAppliedEvent = new SanctionAppliedEvent(sourceClient.getId());
            // the sanction cost should always be set since the client should prevent any sanction requests from being emitted
            // if the user doesn't have enough tokens to issue the request.
            sanctionAppliedEvent.setSanctionCost(sanctionCost);
            // the sanction penalty may be in the range [1, RoundConfiguration.getSanctionPenalty()] -
            // if target has less than the actual sanction penalty they just get their tokens reduced to 0.
            sanctionAppliedEvent.setSanctionPenalty(subtractedTokens);
            sanctionAppliedEvent.setTarget(targetClient.getId());
            persister.store(sanctionAppliedEvent);
            // add sanction request to the target client so they can figure out who just sanctioned them
            sourceClient.getLatestSanctions().add(request);
            targetClient.getLatestSanctions().add(request);
            logger.info("target client " + targetClient.getId() + " has sanctions: " + targetClient.getLatestSanctions());
            transmit(new ClientMessageEvent(sourceClient.getId(),
                    String.format("Subtracting %d tokens from # %d at the cost of %d to yourself.",
                            subtractedTokens,
                            targetClient.getAssignedNumber(),
                            sanctionCost)));
            transmit(new ClientMessageEvent(targetClient.getId(),
                    String.format("# %d subtracted %d tokens from you.",
                            sourceClient.getAssignedNumber(),
                            subtractedTokens)));
        }

        private void initializeFacilitatorHandlers() {
            // facilitator handlers
            addEventProcessor(new EventTypeProcessor<FacilitatorRegistrationRequest>(FacilitatorRegistrationRequest.class) {
                public void handle(FacilitatorRegistrationRequest event) {
                    // remap the facilitator ID and remove from the clients list.
                    facilitatorId = event.getId();
                    synchronized (clients) {
                        clients.remove(facilitatorId);
                    }
                }
            });
            addEventProcessor(new EventTypeProcessor<ShowRequest>(ShowRequest.class, true) {
                public void handle(ShowRequest request) {
                    if (request.getId().equals(facilitatorId)) {
                        for (Identifier id: clients.keySet()) {
                            transmit(request.copy(id));
                        }
                        sendFacilitatorMessage("Received " + request + " from facilitator, copied & broadcastto all clients.");
                    }
                    else {
                        sendFacilitatorMessage("Ignoring show request from non facilitator id: " + request.getId());
                    }
                }
            });
//            addEventProcessor(new EventTypeProcessor<ShowInstructionsRequest>(ShowInstructionsRequest.class) {
//                public void handle(ShowInstructionsRequest event) {
//                    // FIXME: assign groups?
//                    if (event.getId().equals(facilitatorId)) {
//                        logger.info("Show Instructions request from facilitator - showing round instructions.");
//                        for (Identifier id : clients.keySet()) {
//                            transmit(new ShowInstructionsRequest(id));
//                        }
//                    }
//                    else {
//                        logger.warning("Ignoring show instructions request from id: " + event.getId());
//                    }
//                }
//            });
//            addEventProcessor(new EventTypeProcessor<ShowTrustGameRequest>(ShowTrustGameRequest.class) {
//                public void handle(ShowTrustGameRequest event) {
//                    if (event.getId().equals(facilitatorId)) {
//                        logger.info("Showing trust game.");
//                        for (Identifier id : clients.keySet()) {
//                            transmit(new ShowTrustGameRequest(id));
//                        }
//                    }
//                    else {
//                        logger.warning("Ignoring show instructions request from id: " + event.getId());
//                    }
//                }
//            });
            addEventProcessor(new EventTypeProcessor<BeginRoundRequest>(BeginRoundRequest.class) {
                public void handle(BeginRoundRequest event) {
                    if (event.getId().equals(facilitatorId)) {
                        if (isReadyToStartRound()) {
                            logger.info("Begin round request from facilitator - starting round.");
                            experimentStarted = true;
                            Utils.notify(roundSignal);
                        }
                        else {
                            sendFacilitatorMessage(String.format(
                                    "Couldn't start round, waiting on %d of %d quizzes", 
                                    numberOfSubmittedQuizzes, 
                                    clients.size()));
                        }
                    }
                    else {
                        sendFacilitatorMessage("Ignoring begin round request from id: " + event.getId());
                    }
                }
            });
            addEventProcessor(new EventTypeProcessor<EndRoundRequest>(EndRoundRequest.class) {
                public void handle(EndRoundRequest request) {
                    if (request.getId().equals(facilitatorId)) {
                        // set current round duration to expire?
                        currentRoundDuration.stop();
                    }
                }
            });
            addEventProcessor(new EventTypeProcessor<SurveyIdSubmissionRequest>(SurveyIdSubmissionRequest.class) {
                @Override
                public void handle(SurveyIdSubmissionRequest request) {
                    ClientData clientData = clients.get(request.getId());
                    clientData.getId().setSurveyId(request.getSurveyId());
                    sendFacilitatorMessage(String.format("Storing survey id %s for client %s", request.getSurveyId(), clientData));
                }
            });
            addEventProcessor(new EventTypeProcessor<TrustGameSubmissionRequest>(TrustGameSubmissionRequest.class) {
                int numberOfSubmissions = 0;
                @Override
                public void handle(TrustGameSubmissionRequest request) {
                    if (getCurrentRoundConfiguration().isTrustGameEnabled()) {
                        // basic sanity check
                        ClientData clientData = clients.get(request.getId());
                        clientData.setTrustGamePlayerOneAmountToKeep(request.getPlayerOneAmountToKeep());
                        clientData.setTrustGamePlayerTwoAmountsToKeep(request.getPlayerTwoAmountsToKeep());
                        persister.store(request);
                        transmit(new TrustGameSubmissionEvent(facilitatorId, request));
                        numberOfSubmissions++;
                        sendFacilitatorMessage(String.format("%d trust game submissions (%s)", numberOfSubmissions, request));
                    }
                    if (numberOfSubmissions >= clients.size()) {
                        // once all clients have submitted their decisions, execute the trust game.
                        for (GroupDataModel group : serverDataModel.getGroups()) {
                            LinkedList<ClientData> clientList = new LinkedList<ClientData>(group.getClientDataMap().values());
                            Collections.shuffle(clientList);
                            // FIXME: arbitrary choice to save the first one to pair up with the last one as well.
                            ClientData first = clientList.getFirst();
                            for (Iterator<ClientData> iter = clientList.iterator(); iter.hasNext();) {
                                ClientData playerOne = iter.next();
                                ClientData playerTwo = first;
                                if (iter.hasNext()) {
                                    playerTwo = iter.next();
                                }
                                String trustGameResults = serverDataModel.calculateTrustGame(playerOne, playerTwo);
                                sendFacilitatorMessage(String.format("Pairing %s with %s for trust game resulted in: %s", playerOne, playerTwo, trustGameResults));
                            }
                        }
                        numberOfSubmissions = 0;
                    }
                }
            });

            addEventProcessor(new EventTypeProcessor<BeginChatRoundRequest>(BeginChatRoundRequest.class) {
                public void handle(BeginChatRoundRequest request) {
                    if (getCurrentRoundConfiguration().isChatEnabled()) {
                        // FIXME: need to handle properly corner case where chat is enabled before the first round
                        // - at that point the clients haven't been added to any groups yet.
                        // another way to handle this is to have the clients added
                        // to groups when the show instructions request is handled..
                        initializeGroups();
                        /*
                        if (getCurrentRoundConfiguration().isFirstRound()) {
                            shuffleParticipants();
                        }

                        for (Map.Entry<Identifier, ClientData> entry : clients.entrySet()) {
                            Identifier id = entry.getKey();
                            ClientData clientData = entry.getValue();
                            // FIXME: hacky, get rid of this.
                            if (clientData.getGroupDataModel() == null) {
                                // we haven't added this client to the server data model yet. Add them now..
                                // FIXME: will this cause problems if we invoke shuffleParticipants() later? I.e.,
                                // the clients get added to the server data model for the purposes of the chat
                                // and then when they start the actual round they get reshuffled? Need to
                                // rethink clearly/carefully how clients and when clients get added to the server
                                // data model...!
                                serverDataModel.addClient(clientData);
                            }
                            transmit(new BeginChatRoundRequest(id, clientData.getGroupDataModel()));
                            // FIXME: should we initialize the persister now?
                            // just store communication traffic in a separate text file.

                        }
                        */
                    }
                }
            });
            // FIXME: handle reconfiguration requests from facilitator
        }

        protected boolean isReadyToStartRound() {
            if (getCurrentRoundConfiguration().isQuizEnabled()) {
                return numberOfSubmittedQuizzes >= clients.size(); 
            }
            return true;
        }

        private void relayChatRequest(ChatRequest request) {
            Identifier source = request.getSource();
            Identifier target = request.getTarget();
            if (Identifier.ALL.equals(target)) {
                // relay to all clients in this client's group.
                ClientData clientData = clients.get(source);
                getLogger().info(String.format("chat from %s: [ %s ]", clientData.toString(), request));
                // check for field of vision
                RoundConfiguration currentConfiguration = getCurrentRoundConfiguration();
                if (currentConfiguration.isFieldOfVisionEnabled()) {
                    // FIXME: replace with clientData.getFieldOfVision?

                    Circle circle = new Circle(clientData.getPosition(), currentConfiguration.getViewSubjectsRadius());
                    sendChatEvent(request, clientData.getGroupDataModel().getClientIdentifiersWithin(circle));
                }
                else {
                    sendChatEvent(request, clientData.getGroupDataModel().getClientIdentifiers());
                }
            }
            else {
                getLogger().info(String.format("%s sending [%s] to target [%s]", request.getSource(), request, request.getTarget()));
                ChatEvent chatEvent = new ChatEvent(request.getTarget(), request.toString(), request.getSource());
                transmit(chatEvent);
            }
            persister.store(request);
        }

        private void sendChatEvent(ChatRequest request, Collection<Identifier> identifiers) {
            for (Identifier targetId : identifiers) {
                ChatEvent chatEvent = new ChatEvent(targetId, request.toString(), request.getSource(), true);
                transmit(chatEvent);
            }
        }
        
        private void sendFacilitatorMessage(String message) {
            logger.info(message);
            transmit(new FacilitatorMessageEvent(facilitatorId, message));
        }

        // FIXME: remove Dispatcher reference if it's unused.
        public void execute(Dispatcher dispatcher) {
            switch (serverState) {
                case ROUND_IN_PROGRESS:
                    // process incoming information
                    if (currentRoundDuration.hasExpired()) {
                        // perform token adjustment if needed.
                        for (GroupDataModel group : serverDataModel.getGroups()) {
                            if (group.getActiveEnforcementMechanism().hasMonitor()) {
                                group.applyMonitorTax();
                            }
                        }
                        stopRound();
                        break;
                    }

                    roundProcessor.execute();
                    // Thread.yield();
                    Utils.sleep(SERVER_SLEEP_INTERVAL);
                    break;
                case WAITING:
                    // FIXME: there is an inherent nastiness going on with this model of control flow
                    // the problem is this: when we first spin up the server, there are no connected clients.  control flow
                    // enters here, we initialize the round (with no participants, etc.) and then wait on the quiz signal
                    
                    // the issue is that we need to initialize the groups at some clear, well-defined time.
                    // we have to do it after all the clients are connected, so perhaps showInstructions can be the time to do it?
                    // the previous way which I've been slowly refactoring was to do it on the beginning of the first round (as a special case)
                    // in the handler for BeginRoundRequest) and to do it at the end of every round.  Probably better to do it in round initialization
                    
                    // initialize persister first so we store all relevant events.
                    // persister MUST be initialized early so that we store pre-round events like QuizResponseEvent, ChatEvent, and the various Ranking
                    // requests.
                    persister.initialize(getCurrentRoundConfiguration());
                    getLogger().info("Initialized persister, waiting for facilitator signal to start next round.");
                    if (getCurrentRoundConfiguration().isQuizEnabled()) {
                        getLogger().info("Waiting for all quizzes to be submitted.");
                        Utils.waitOn(quizSignal);
                    }
                    // then wait for the signal from the facilitator to actually start the round (a chat session might occur or a voting session).
                    Utils.waitOn(roundSignal);

                    startRound();
                    break;
                default:
                    throw new RuntimeException("Should never get here.");
            }
        }

        private void processRound3d() {
            boolean secondHasPassed = secondTick.hasExpired();
            if (secondHasPassed) {
                int secondsPerYear = getConfiguration().getCurrentParameters().getResourceAgingSecondsPerYear();
                for (GroupDataModel group : serverDataModel.getGroups()) {
                    // update resource age
                    if (secondTick.getStartCount() % secondsPerYear == 0) {
                        resourceDispenser.updateResourceAge(group);
                    }
                    // renew resources
                    resourceDispenser.generateResources(group);
                }
                secondTick.restart();
            }
            // send new information to each client
            long timeLeft = currentRoundDuration.getTimeLeft();
            for (ClientData data : clients.values()) {
                transmit(new SynchronizeClientEvent(data, timeLeft));
            }
            serverDataModel.clearDiffLists();
        }

        private void processRound2d() {
            boolean secondHasPassed = secondTick.hasExpired();
            if (secondHasPassed) {
                // handle rotating monitors.
                if (getCurrentRoundConfiguration().isRotatingMonitorEnabled()
                        && currentRoundDuration.getElapsedTimeInSeconds() % monitorRotationInterval == 0)
                {
                    for (GroupDataModel group : serverDataModel.getGroups()) {
                        boolean rotated = group.rotateMonitorIfNecessary();
                        if (rotated) {
                            // send new roles to all clients
                            // FIXME: this is inefficient, we could synchronize twice.
                            for (ClientData clientData : group.getClientDataMap().values()) {
                                transmit(new SynchronizeClientEvent(clientData, currentRoundDuration.getTimeLeft()));
                            }
                        }

                    }
                }
                resourceDispenser.generateResources();
                secondTick.restart();
            }
            for (GroupDataModel group : serverDataModel.getGroups()) {
                for (ClientData clientData : group.getClientDataMap().values()) {
                    // ask each client if it wants to grab a token, wherever it is.
                    clientData.collectToken();
                }
            }

            for (ClientData clientData : clients.values()) {
                if (shouldSynchronize(clientData.getAssignedNumber())) {
                    transmit(new SynchronizeClientEvent(clientData, currentRoundDuration.getTimeLeft()));
                }
                else {
                    transmit(new ClientPositionUpdateEvent(clientData, currentRoundDuration.getTimeLeft()));
                }
            }
            // send the current ServerGameState to the facilitator
            if (shouldUpdateFacilitator()) {
                transmit(new FacilitatorUpdateEvent(facilitatorId, serverDataModel, currentRoundDuration.getTimeLeft()));
            }
            // post-process cleanup
            // for now, the only thing we need to do is clear the food added/removed lists for each group.
            serverDataModel.postProcessCleanup();
        }

        private boolean shouldUpdateFacilitator() {
            long startCount = secondTick.getStartCount();
            return (startCount < 3);
        }

        private boolean shouldSynchronize(int assignedNumber) {
            long startCount = secondTick.getStartCount();
            return (startCount < 3) || ((startCount % SYNCHRONIZATION_FREQUENCY) == (assignedNumber * 10));
        }

        private void stopRound() {
            serverState = ServerState.WAITING;
            // FIXME: not needed, persister.persist() automatically adds this.
            // persister.store(new RoundEndedMarkerEvent());
            sendEndRoundEvents();
            if (getCurrentRoundConfiguration().isPostRoundSanctioningEnabled()) {
                // stop most of the round but don't persist/cleanup yet.
                // block until we receive all postround sanctioning events.
                // FIXME: use new java.util.concurrent constructs? CountDownLatch or CyclicBarrier?
                // postRoundSanctionLatch = new CountDownLatch(clients.size());
                // try { postRoundSanctionLatch.await(); }
                // catch (InterruptedException ignored) {}
                Utils.waitOn(postRoundSanctioningSignal);
            }
            persistRound();
            cleanupRound();
            // FIXME: make sure this is needed and document.
            Utils.sleep(2000);
            advanceToNextRound();
        }

        private void sendEndRoundEvents() {
            // send each client some debriefing information:
            // 1. avg number of tokens collected in this round for the group
            // 2. total number of tokens collected by this client
            // 3. number of tokens collected by this client in this round.
            transmit(new FacilitatorEndRoundEvent(facilitatorId, serverDataModel));
            boolean lastRound = getConfiguration().isLastRound();
            for (Map.Entry<Identifier, ClientData> clientDataEntry : clients.entrySet()) {
                Identifier id = clientDataEntry.getKey();
                ClientData clientData = clientDataEntry.getValue();
                transmit(new EndRoundEvent(id, clientData, lastRound));
            }
        }

        private void persistRound() {
            persister.persist(serverDataModel);
        }

        private void cleanupRound() {
            numberOfSubmittedQuizzes = 0;
            serverDataModel.cleanupRound();
            for (ClientData clientData : clients.values()) {
                clientData.reset();
            }
        }

        private void advanceToNextRound() {
            if (getConfiguration().isLastRound()) {
                // stop the experiment.
                serverState = ServerState.WAITING;
                return;
            }
            RoundConfiguration nextRoundConfiguration = getConfiguration().nextRound();
            serverDataModel.setRoundConfiguration(nextRoundConfiguration);
            initializeRoundProcessor();
            logger.info("Advancing to round # " + getConfiguration().getCurrentRoundNumber());
            // send the next round configuration to each client
            for (Identifier id : clients.keySet()) {
                transmit(new SetConfigurationEvent<RoundConfiguration>(id, nextRoundConfiguration));
            }
            transmit(new SetConfigurationEvent<RoundConfiguration>(facilitatorId, nextRoundConfiguration));
        }

        private boolean shouldShuffleParticipants() {
            RoundConfiguration currentRoundConfiguration = getCurrentRoundConfiguration();
            RoundConfiguration previousRoundConfiguration = getConfiguration().getPreviousRoundConfiguration();
            // when do we _have_ to shuffle participants?
            // 1. when randomize-groups is set for the next round
            // 2. when we move from a private property round to a open access round
            // 3. in general, when the clients per group in the current round is different from the
            // clients per group in the next round (FIXME: is this too broad or can #2 just be a special case of this?)
            return currentRoundConfiguration.shouldRandomizeGroup()
                    || (previousRoundConfiguration.getClientsPerGroup() != currentRoundConfiguration.getClientsPerGroup());
        }

        private void shuffleParticipants() {
            List<ClientData> randomizedClients = new ArrayList<ClientData>(clients.values());
            Collections.shuffle(randomizedClients);
            // clear all existing group linkages
            serverDataModel.clear();
            // generate new group linkages
            for (ClientData clientData : randomizedClients) {
                serverDataModel.addClient(clientData);
            }
        }

        private void initializeClientPositions() {
            // reinitialize all client positions. We don't have to do this if we are randomizing the group
            // because client positions get initialized when they are added to the group
            // (during the randomization process).
            for (ClientData clientData : clients.values()) {
                clientData.initializePosition();
            }
        }
        
        private void initializeGroups() {
            // reset group linkages if necessary
            if (shouldShuffleParticipants()) {
                logger.info("Shuffling participants");
                shuffleParticipants();
            }
            else {
                logger.info("Didn't need to shuffle participants : " + getCurrentRoundConfiguration());
                // shuffleParticipants automatically initializes the client positions
                // if we don't shuffle, we need to manually re-initialize them.
                initializeClientPositions();
            }
            // set up the resource dispenser, generates the initial resource distributions for the
            // groups, must be done after creating the client group relationships.
            resourceDispenser.initialize();
        }

        private void startRound() {
            RoundConfiguration roundConfiguration = getCurrentRoundConfiguration();
            // actually start the round once we receive the facilitator signal.
            getLogger().info("Initializing groups.");
            // FIXME: what if we want groups before the round actually begins..?
            initializeGroups();
            // send RoundStartedEvents to all connected clients
            for (Map.Entry<Identifier, ClientData> entry : clients.entrySet()) {
                Identifier id = entry.getKey();
                ClientData data = entry.getValue();
                transmit(new RoundStartedEvent(id, data.getGroupDataModel()));
            }
            persister.store(new RoundStartedMarkerEvent());
            // start timers
            currentRoundDuration = roundConfiguration.getRoundDuration();
            if (roundConfiguration.isVotingAndRegulationEnabled()) {
                monitorRotationInterval = Math.max(Duration.toSeconds(currentRoundDuration.getTimeLeft()) / roundConfiguration.getClientsPerGroup(), 1);
                logger.info("monitor rotation interval: " + monitorRotationInterval);
            }
            currentRoundDuration.start();
            transmit(new FacilitatorUpdateEvent(facilitatorId, serverDataModel, currentRoundDuration.getTimeLeft()));
            secondTick.start();
            serverState = ServerState.ROUND_IN_PROGRESS;
        }
    }

    /**
     * Main entry point. Configuration options:
     * 
     * conf.dir, e.g -Dconf.dir=path/to/configuration
     * 
     */
    public static void main(String[] args) {
        ForagingServer server = new ForagingServer();
        server.start();
        server.repl();
    }
}
