package com.knowledgepixels.registry;

import static com.knowledgepixels.registry.RegistryDB.collection;
import static com.knowledgepixels.registry.RegistryDB.get;
import static com.knowledgepixels.registry.RegistryDB.getOne;
import static com.knowledgepixels.registry.RegistryDB.has;
import static com.knowledgepixels.registry.RegistryDB.increateStateCounter;
import static com.knowledgepixels.registry.RegistryDB.insert;
import static com.knowledgepixels.registry.RegistryDB.loadNanopub;
import static com.knowledgepixels.registry.RegistryDB.set;
import static com.knowledgepixels.registry.RegistryDB.setValue;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.ascending;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.bson.Document;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.nanopub.Nanopub;
import org.nanopub.NanopubImpl;
import org.nanopub.extra.index.IndexUtils;
import org.nanopub.extra.index.NanopubIndex;
import org.nanopub.extra.security.KeyDeclaration;
import org.nanopub.extra.setting.IntroNanopub;
import org.nanopub.extra.setting.NanopubSetting;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

import net.trustyuri.TrustyUriUtils;

public class TaskManager {

	private static MongoCollection<Document> tasks = collection("tasks");

	private TaskManager() {}

	static void runTasks() {
		if (!RegistryDB.isInitialized()) {
			schedule(task("init-db"));
		}
		while (true) {
			FindIterable<Document> taskResult = tasks.find().sort(ascending("not-before"));
			Document task = taskResult.first();
			if (task != null && task.getLong("not-before") < System.currentTimeMillis()) {
				try {
					runTask(task);
				} catch (Exception ex) {
					ex.printStackTrace();
					error(ex.getMessage());
					break;
				}
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException ex) {
				ex.printStackTrace();
			}
		}
	}

	static void runTask(Document task) throws Exception {
		String action = task.getString("action");
		if (action == null) throw new RuntimeException("Action is null");
		System.err.println("Running task: " + action);

		// TODO Proper transactions / roll-back

		if (action.equals("init-db")) {

			setValue("server-info", "status", "launching");
			increateStateCounter();
			if (RegistryDB.isInitialized()) error("DB already initialized");
			setValue("server-info", "setup-id", Math.abs(new Random().nextLong()));
			schedule(task("load-config"));

		} else if (action.equals("load-config")) {

			if (System.getenv("REGISTRY_COVERAGE_TYPES") != null) {
				setValue("server-info", "coverage-types", System.getenv("REGISTRY_COVERAGE_TYPES"));
			}
			if (System.getenv("REGISTRY_COVERAGE_AGENTS") != null) {
				setValue("server-info", "coverage-agents", System.getenv("REGISTRY_COVERAGE_AGENTS"));
			}
			setValue("server-info", "status", "initializing");
			schedule(task("load-setting"));

		} else if (action.equals("load-setting")) {

			NanopubSetting settingNp = new NanopubSetting(new NanopubImpl(new File("/data/setting.trig")));
			String settingId = TrustyUriUtils.getArtifactCode(settingNp.getNanopub().getUri().stringValue());
			setValue("setting", "original", settingId);
			setValue("setting", "current", settingId);
			loadNanopub(settingNp.getNanopub());
			List<Document> bootstrapServices = new ArrayList<>();
			for (IRI i : settingNp.getBootstrapServices()) {
				bootstrapServices.add(new Document("_id", i.stringValue()));
			}
			setValue("setting", "bootstrap-services", bootstrapServices);
			setValue("server-info", "status", "loading");

			insert("trust-paths",
					new Document("_id", "@")
						.append("sorthash", "")
						.append("agent", "@")
						.append("pubkey", "@")
						.append("depth", 0)
						.append("ratio", 1.0d)
				);

			NanopubIndex agentIndex = IndexUtils.castToIndex(NanopubRetriever.retrieveNanopub(settingNp.getAgentIntroCollection().stringValue()));
			loadNanopub(agentIndex);
			for (IRI el : agentIndex.getElements()) {
				String declarationAc = TrustyUriUtils.getArtifactCode(el.stringValue());

				insert("endorsements",
						new Document("agent", "@")
							.append("pubkey", "@")
							.append("endorsed-nanopub", declarationAc)
							.append("source", settingId)
							.append("status", "to-retrieve")
					);

			}
			insert("agents",
					new Document("agent", "@")
						.append("pubkey", "@")
						.append("status", "visited")
						.append("depth", 0)
				);

			System.err.println("Starting iteration at depth 0");
			schedule(task("load-declarations").append("depth", 1));

		} else if (action.equals("load-declarations")) {

			int depth = task.getInteger("depth");

			if (has("endorsements", new Document("status", "to-retrieve"))) {
				Document d = getOne("endorsements", new Document("status", "to-retrieve"));

				IntroNanopub agentIntro = new IntroNanopub(NanopubRetriever.retrieveNanopub(d.getString("endorsed-nanopub")));
				loadNanopub(agentIntro.getNanopub());
				if (agentIntro.getUser() != null) {
					// TODO Why/when is user null?
					String agentId = agentIntro.getUser().stringValue();
					for (KeyDeclaration kd : agentIntro.getKeyDeclarations()) {
						String pubkeyHash = Utils.getHash(kd.getPublicKeyString());

						insert("trust-edges",
								new Document("from-agent", d.getString("agent"))
									.append("from-pubkey", d.getString("pubkey"))
									.append("to-agent", agentId)
									.append("to-pubkey", pubkeyHash)
									.append("source", d.getString("source"))
									.append("invalidated", false)
							);

						Document agent = new Document("agent", agentId).append("pubkey", pubkeyHash);
						if (!has("agents", agent)) {
							insert("agents", agent.append("status", "seen").append("depth", depth));
						}
					}

					set("endorsements", d, new Document("status", "retrieved"));
				} else {
					set("endorsements", d, new Document("status", "discarded"));
				}

				schedule(task("load-declarations").append("depth", depth));

			} else {
				schedule(task("expand-trust-paths").append("depth", depth));
			}

		} else if (action.equals("expand-trust-paths")) {

			int depth = task.getInteger("depth");
			String currentSetting = get("setting", "current").toString();

			Document findAgents = new Document("status", "visited").append("depth", depth - 1);

			if (has("agents", findAgents)) {

				Document d = collection("agents").find(findAgents).cursor().next();
	
				String agentId = d.getString("agent");
				String pubkeyHash = d.getString("pubkey");

				// TODO Consider also maximum ratio?
				Document trustPath = collection("trust-paths").find(
						new Document("agent", agentId).append("pubkey", pubkeyHash).append("depth", depth - 1)
					).sort(new Document("sorthash", 1)).cursor().tryNext();

				if (trustPath != null) {
					// Only first matching trust path is considered
					Document findTerm = new Document("from-agent", agentId).append("from-pubkey", pubkeyHash).append("invalidated", false);
					MongoCursor<Document> edgeCursor = collection("trust-edges").find(findTerm).cursor();
					Map<String,Document> newPaths = new HashMap<>();
					while (edgeCursor.hasNext()) {
						Document edgeDoc = edgeCursor.next();
						String targetAgentId = edgeDoc.getString("to-agent");
						String targetPubkeyHash = edgeDoc.getString("to-pubkey");
						String pathId = trustPath.getString("_id") + " " + targetAgentId + ">" + targetPubkeyHash;
						String sortHash = Utils.getHash(currentSetting + " " + pathId);
						if (!newPaths.containsKey(pathId)) {
							newPaths.put(pathId, new Document("_id", pathId).append("sorthash", sortHash)
									.append("agent", targetAgentId).append("pubkey", targetPubkeyHash).append("depth", depth));
						}
					}
					double ratio = (trustPath.getDouble("ratio") * 0.9) / newPaths.size();
					for (String pathId : newPaths.keySet()) {
						insert("trust-paths", newPaths.get(pathId).append("ratio", ratio));
					}
					// TODO Make status dependent on ratio:
					set("agents", d, new Document("status", "processed"));
				} else {
					// Check it again in next iteration:
					set("agents", d, new Document("depth", depth + 1));
				}
				schedule(task("expand-trust-paths").append("depth", depth));
	
			} else {

				schedule(task("load-core").append("depth", depth));

			}

		} else if (action.equals("load-core")) {

			int depth = task.getInteger("depth");

			if (has("agents", new Document("status", "seen"))) {

				Document d = getOne("agents", new Document("status", "seen"));
				String agentId = d.getString("agent");
				String pubkeyHash = d.getString("pubkey");

				// TODO check intro limit
				String introType = Utils.INTRO_TYPE.stringValue();
				String introTypeHash = Utils.getHash(introType);
				if (!has("lists", new Document("pubkey", pubkeyHash).append("type", introTypeHash))) {
					// TODO Why/when is list already loaded?
					Document introList = new Document("pubkey", pubkeyHash).append("type", introTypeHash).append("status", "loading");
					insert("lists", introList);
					NanopubRetriever.retrieveNanopubs(introType, pubkeyHash, e -> {
						loadNanopub(NanopubRetriever.retrieveNanopub(e.get("np")), introType, pubkeyHash);
					});
					set("lists", introList, new Document("status", "loaded"));
				}

				// TODO check endorsement limit
				String endorseType = Utils.APPROVAL_TYPE.stringValue();
				String endorseTypeHash = Utils.getHash(endorseType);
				if (!has("lists", new Document("pubkey", pubkeyHash).append("type", endorseTypeHash))) {
					Document endorseList = new Document("pubkey", pubkeyHash).append("type", endorseTypeHash).append("status", "loading");
					insert("lists", endorseList);
					NanopubRetriever.retrieveNanopubs(endorseType, pubkeyHash, e -> {
						Nanopub nanopub = NanopubRetriever.retrieveNanopub(e.get("np"));
						loadNanopub(nanopub, endorseType, pubkeyHash);
						String sourceNpId = TrustyUriUtils.getArtifactCode(nanopub.getUri().stringValue());
						for (Statement st : nanopub.getAssertion()) {
							if (!st.getPredicate().equals(Utils.APPROVES_OF)) continue;
							if (!(st.getObject() instanceof IRI)) continue;
							if (!agentId.equals(st.getSubject().stringValue())) continue;
							String objStr = st.getObject().stringValue();
							if (!TrustyUriUtils.isPotentialTrustyUri(objStr)) continue;
							String endorsedNpId = TrustyUriUtils.getArtifactCode(objStr);
							collection("endorsements").insertOne(
									new Document("agent", agentId)
										.append("pubkey", pubkeyHash)
										.append("endorsed-nanopub", endorsedNpId)
										.append("source", sourceNpId)
										.append("status", "to-retrieve")
								);
						}
					});
					set("lists", endorseList, new Document("status", "loaded"));
				}

				set("agents", d, new Document("status", "visited"));

				schedule(task("load-core").append("depth", depth));

			} else {
				schedule(task("finish-iteration").append("depth", depth));
			}

		} else if (action.equals("finish-iteration")) {

			int depth = task.getInteger("depth");
			if (depth == 10) {
				schedule(task("loading-done"));
			} else {
				System.err.println("Progressing iteration at depth " + (depth + 1));
				schedule(task("load-declarations").append("depth", depth + 1));
			}

		} else if (action.equals("loading-done")) {

			setValue("server-info", "status", "ready");
			System.err.println("Loading done");

		} else {

			error("Unknown task: " + action);

		}

		tasks.deleteOne(eq("_id", task.get("_id")));
	}

	private static void error(String message) {
		setValue("server-info", "status", "hanging");
		throw new RuntimeException(message);
	}

	private static void schedule(Document task) {
		tasks.insertOne(task);
	}

	private static Document task(String name) {
		return task(name, 0l);
	}

	private static Document task(String name, long delay) {
		return new Document("not-before", System.currentTimeMillis() + delay).append("action", name);
	}

}
