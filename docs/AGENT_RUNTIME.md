# Agent Runtime Refactor Plan

## Goal

Refactor the two AI paths into one Agent Runtime for pet and plant care. The runtime owns identity, conversation state, care context, multimodal input, tool selection, response synthesis, and observability.

## Confirmed Decisions

- Keep MySQL plus SQLite for the first implementation.
- Return synchronous JSON in the first API version.
- Store images locally under `uploads` for the first version.
- Continue using `HttpSession` for authentication.
- Build a new `agent.html` workspace instead of replacing `chat.html` during the migration.

## Baseline

The refactor started from Git commit `8e307a2`. The current compatibility paths are:

- `/api/care/qa`: existing care chat and image chat.
- `/api/ai/chat-with-tools`: existing tool-calling API.
- `/api/care/identify`: plant and pet identification.
- `/api/disease/diagnose`: plant disease and pet skin diagnosis.

## Target API

`POST /api/agent/messages` accepts:

```json
{
  "conversationId": "optional",
  "message": "required when no image is supplied",
  "artifactId": "preferred image reference from POST /api/agent/artifacts",
  "imageBase64": "compatibility input, stored as an artifact before orchestration",
  "subjectType": "PET or PLANT",
  "subjectId": 123,
  "allowedTools": ["optional read-tool subset"]
}
```

It returns a synchronous response containing the final reply, conversation ID, artifact reference, trace ID, tool history, generated file URLs, and action cards.

## Implementation Stages

1. **Context foundation**
   - Introduce `AgentContext`.
   - Inject context into tools with `@AgentContextParam`.
   - Hide context parameters from LLM tool schemas.
   - Propagate context through concurrent tool execution.

2. **Runtime v1**
   - Build conversation state from `HttpSession`.
   - Normalize owned `CareTarget` records into `CareSubject` compatibility views.
   - Store uploaded images under `uploads/agent` and reference them with `Artifact`.
   - Store conversation turns in `agent_conversation` and `agent_message`.
   - Return tool traces and generated files.

3. **Domain event model**
   - Add artifacts and action confirmations.
   - Normalize care actions into `CareEvent`.
   - Keep legacy repositories as compatibility readers during migration.

4. **Memory and retrieval**
   - Add user, conversation, and source filters to vector search.
   - Persist vector ownership in SQLite.
   - Allow only user-owned conversation memory and public knowledge documents.

5. **Write actions**
   - Return confirmation cards before creating reminders, saving records, consuming inventory, or publishing content.
   - Execute confirmed writes through the Tool Broker only.

6. **Legacy cleanup**
   - Route authenticated `/api/care/qa` requests through the runtime behind `agent.runtime.enabled`.
   - Keep old APIs as facades until browser flows are migrated.
   - Remove manual intent routing after regression tests pass.

## Implemented Runtime Surface

- `POST /api/agent/messages`
- `GET /api/agent/subjects`
- `POST /api/agent/conversations/new`
- `GET /api/agent/tools`
- `POST /api/agent/artifacts`
- `GET /api/agent/artifacts/{id}/content`
- `POST /api/agent/confirmations/{id}/confirm`
- `POST /api/agent/confirmations/{id}/cancel`

The runtime stores structured state in `agent_conversation`, `agent_message`, `agent_tool_trace`, `artifact`, `care_subject`, `care_event`, and `action_confirmation`. Existing business tables remain in place as compatibility readers and execution targets.

## Tool Policy

Automatic in v1:

- `getCurrentTime`, `getWeather`, `queryWeather`
- `webSearch`, `professionalSearch`
- `analyzeImage`, `diagnoseDisease`
- `queryPetCare`, `queryPlantSafety`, `queryFoodSafety`
- `searchNearbyService`, `triageSymptoms`
- `checkMedication`, `compareImages`, `listCareReminders`

The planner may propose these writes, but the Tool Broker stores an `action_confirmation` instead of executing them. They run only after the owner confirms:

- `createCareReminder`, `completeCareReminder`
- `saveMedication`, `generateCarePlan`
- `generateImage`, `editImage`

## Regression Cases

1. Weather question selects a weather tool.
2. Nearby hospital question preserves Amap navigation URLs.
3. Pet food safety question returns toxicity and emergency guidance.
4. Plant toxicity question returns toxicity and first aid guidance.
5. Care question includes the selected target and recent care records.
6. Image upload followed by a question invokes image analysis.
7. Leaf or skin image invokes disease diagnosis.
8. Existing care QA still works with the runtime disabled.
9. Unauthenticated agent requests return HTTP 401.
10. A subject ID belonging to another user is rejected.

## Rollback

- Revert the failed stage commit.
- For an immediate behavioral rollback without a revert, set `agent.runtime.enabled=false` or `AGENT_RUNTIME_ENABLED=false`.
- The legacy `/api/care/qa` path remains compiled and selectable.
- If runtime behavior fails globally, use the last pre-runtime commit `8e307a2`.
- Do not reset a branch containing uncommitted work.
