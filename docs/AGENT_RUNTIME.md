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
  "imageBase64": "optional",
  "subjectType": "PET or PLANT",
  "subjectId": 123,
  "allowedTools": ["optional read-tool subset"]
}
```

It returns a synchronous response containing the final reply, conversation ID, trace ID, tool history, generated file URLs, and future action cards.

## Implementation Stages

1. **Context foundation**
   - Introduce `AgentContext`.
   - Inject context into tools with `@AgentContextParam`.
   - Hide context parameters from LLM tool schemas.
   - Propagate context through concurrent tool execution.

2. **Runtime v1**
   - Build conversation state from `HttpSession`.
   - Load owned `CareTarget` and recent `CareRecord` data.
   - Store uploaded images through `UserSessionService`.
   - Reuse `ChatMemoryService`.
   - Start with read-only tools.
   - Return tool traces and generated files.

3. **Domain event model**
   - Add artifacts and action confirmations.
   - Normalize care actions into `CareEvent`.
   - Keep legacy repositories as compatibility readers during migration.

4. **Memory and retrieval**
   - Add user, conversation, and source filters to vector search.
   - Separate episodic and semantic memory.
   - Cap retrieved context and record provenance.

5. **Write actions**
   - Return confirmation cards before creating reminders, saving records, consuming inventory, or publishing content.
   - Execute confirmed writes through the Tool Broker only.

6. **Legacy cleanup**
   - Route `/api/care/qa` through the runtime behind a feature switch.
   - Keep old APIs as facades until browser flows are migrated.
   - Remove manual intent routing after regression tests pass.

## Tool Policy

Automatic in v1:

- `getCurrentTime`, `getWeather`, `queryWeather`
- `webSearch`, `professionalSearch`
- `analyzeImage`, `diagnoseDisease`
- `queryPetCare`, `queryPlantSafety`, `queryFoodSafety`
- `searchNearbyService`, `triageSymptoms`
- `checkMedication`, `compareImages`, `listCareReminders`

Deferred until confirmation cards exist:

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
- If runtime behavior fails globally, use the last pre-runtime commit `8e307a2`.
- Do not reset a branch containing uncommitted work.

## Remote History Caution

The requested GitHub remote must not receive the old full `main` history without explicit approval. That history contains previously committed personal PDFs and images. A clean-history branch is required before publishing the rewritten project.
