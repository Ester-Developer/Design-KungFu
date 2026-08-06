Markdown
# 🧠 Autonomous Knowledge Studio & Research Agent

An advanced, interactive research and information-synthesis platform inspired by modern AI assistant workflows. This project empowers users to perform automated, deep web investigations, curate sources dynamically, and generate structured insights through an agentic pipeline.

The application leverages an autonomous ReAct agent to retrieve live web data via the Tavily API, incorporates a state-managed **Human-in-the-Loop (HITL)** validation mechanism for real-time source curation, and compiles precise summaries using advanced language models.

---

## 🎯 Key Architecture & Features

* **Autonomous Research Agent:** Employs a ReAct-based agentic pattern to formulate optimal search strategies and query parameters dynamically.
* **Tavily Search Integration:** Leverages a specialized search tool optimized for LLM agents to extract high-relevance web sources.
* **Human-in-the-Loop (HITL) Workflow:** Features a managed execution breakpoint enabling users to review, filter, and approve or reject gathered sources before synthesis.
* **Stateful Persistence:** Utilizes LangGraph's state management and checkpointing to preserve session states and handle interruption flows seamlessly.
* **Interactive Streamlit Workspace:** Provides a clean, responsive UI featuring interactive source selection cards, live execution feedback, and formatted markdown outputs with export capabilities.

---

## 🛠️ Technology Stack

* **Orchestration:** LangChain & LangGraph (`StateGraph`, checkpointing)
* **Intelligence Layer:** OpenAI Language Models (`GPT-4o-mini`)
* **Search Infrastructure:** Tavily API (Real-time web retrieval)
* **User Interface:** Streamlit (Interactive Python-based frontend)
* **Configuration:** Python-dotenv (Secure environment variable management)

---

## 📸 System Workflow Overview

1. **Input Phase:** The user specifies a target research topic or technical question within the Streamlit interface to initialize the autonomous agent.
2. **Curation Phase (HITL):** The system pauses execution to present discovered web sources, allowing the user to select specific references via interactive checkboxes.
3. **Synthesis Phase:** The agent analyzes and compiles a comprehensive, structured report derived exclusively from the user-approved sources.

---

## ⚙️ Local Installation & Setup

Follow these steps to set up and run the project locally on your machine:

### 1. Clone the Repository
```bash
git clone [https://github.com/YOUR_USERNAME/notebooklm-research-studio.git](https://github.com/YOUR_USERNAME/notebooklm-research-studio.git)
cd notebooklm-research-studio
2. Create and Activate a Virtual Environment
Bash
# Create the virtual environment
python -m venv venv

# Activate on Windows (PowerShell):
.\venv\Scripts\Activate.ps1

# Activate on macOS/Linux:
source venv/bin/activate
3. Install Dependencies
Bash
pip install -r requirements.txt
(If a requirements file is not present, install the core packages directly:)

Bash
pip install streamlit langchain-core langchain-openai langchain-tavily langgraph python-dotenv
4. Configure Environment Variables
Create a file named .env in the root directory of your project and populate your API credentials:

קטע קוד
OPENAI_API_KEY=your_openai_api_key_here
TAVILY_API_KEY=your_tavily_api_key_here
5. Run the Application
Bash
streamlit run app.py
The application will launch automatically in your web browser at http://localhost:8501.

💡 Example Research Topics
The agent is optimized for exploring complex technological, scientific, and current affairs topics. Try testing the system with prompts such as:

"React 19 key features and architectural updates"

"Recent breakthroughs and advancements in Quantum Computing"

"Artificial Intelligence regulatory frameworks in the European Union"

"Comparative state management patterns: Angular vs React"

👥 License
Developed for academic and professional exploration under modern AI engineering guidelines. Distributed under the MIT License.
