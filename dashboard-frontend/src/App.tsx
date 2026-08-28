import { useState, useEffect } from 'react';
import { 
  TrendingUp, 
  DollarSign, 
  AlertTriangle, 
  RefreshCw, 
  Search, 
  Activity, 
  ShieldAlert,
  Clock,
  UserCheck,
  FileText
} from 'lucide-react';

// API base URL
const API_BASE = 'http://localhost:8080/api';

// Types
interface Transaction {
  transactionId: string;
  merchantId: string;
  customerIdHash: string;
  amount: number;
  paymentMethod: string;
  failureCode: string;
  failureMessage: string;
  retryCount: number;
  status: string;
  groundTruthPRecovery?: number;
  createdAt: string;
  updatedAt: string;
}

interface MetricsSummary {
  totalIngested: number;
  recoveredCount: number;
  escalatedCount: number;
  failedCount: number;
  pendingCount: number;
  recoveryRate: number;
  revenueRecovered: number;
  falseRetryCost: number;
  avgRecoveryLatency: number;
}

interface AuditLog {
  id: number;
  transactionId: string;
  step: string;
  actor: string;
  detail: string;
  timestamp: string;
}

interface RecoveryAction {
  id: number;
  transactionId: string;
  actionTaken: string;
  retryAttemptNo: number;
  executedAt: string;
  outcome: string;
  costOfAttempt: number;
  nudgeMessage?: string;
}

export default function App() {
  const [metrics, setMetrics] = useState<MetricsSummary | null>(null);
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [activeTab, setActiveTab] = useState<'ledger' | 'exceptions'>('ledger');
  const [searchTerm, setSearchTerm] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [selectedTxId, setSelectedTxId] = useState<string | null>(null);
  const [selectedTx, setSelectedTx] = useState<Transaction | null>(null);
  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([]);
  const [recoveryActions, setRecoveryActions] = useState<RecoveryAction[]>([]);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  // Load data
  const fetchData = async () => {
    setIsRefreshing(true);
    try {
      // Metrics
      const metricsRes = await fetch(`${API_BASE}/metrics/summary`);
      const metricsData = await metricsRes.json();
      setMetrics(metricsData);

      // Transactions
      const txRes = await fetch(`${API_BASE}/transactions`);
      const txData = await txRes.json();
      setTransactions(txData);

      // Reload selected transaction details if modal open
      if (selectedTxId) {
        const singleRes = await fetch(`${API_BASE}/transactions/${selectedTxId}`);
        if (singleRes.ok) {
          const singleData = await singleRes.json();
          setSelectedTx(singleData);
        }
        
        const auditRes = await fetch(`${API_BASE}/transactions/${selectedTxId}/audit`);
        const auditData = await auditRes.json();
        setAuditLogs(auditData);

        const actRes = await fetch(`${API_BASE}/transactions/${selectedTxId}/actions`);
        const actData = await actRes.json();
        setRecoveryActions(actData);
      }
    } catch (e) {
      console.error('Error fetching dashboard data:', e);
    } finally {
      setIsRefreshing(false);
    }
  };

  useEffect(() => {
    fetchData();
    // Auto-refresh metrics every 5 seconds to show progress
    const interval = setInterval(fetchData, 5000);
    return () => clearInterval(interval);
  }, [selectedTxId]);

  const handleRowClick = async (tx: Transaction) => {
    setSelectedTxId(tx.transactionId);
    setSelectedTx(tx);
    try {
      const auditRes = await fetch(`${API_BASE}/transactions/${tx.transactionId}/audit`);
      const auditData = await auditRes.json();
      setAuditLogs(auditData);

      const actRes = await fetch(`${API_BASE}/transactions/${tx.transactionId}/actions`);
      const actData = await actRes.json();
      setRecoveryActions(actData);
    } catch (e) {
      console.error('Error fetching audit trail:', e);
    }
  };

  const handleResolveException = async (txId: string, action: 'FORCE_RETRY' | 'ACCEPT_LOSS') => {
    setActionLoading(txId);
    try {
      const res = await fetch(`${API_BASE}/exceptions/${txId}/resolve`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action }),
      });
      if (res.ok) {
        fetchData();
      } else {
        const err = await res.json();
        alert(err.error || 'Failed to resolve exception.');
      }
    } catch (e) {
      console.error('Error resolving exception:', e);
    } finally {
      setActionLoading(null);
    }
  };

  // Filter transactions
  const filteredTx = transactions.filter(tx => {
    const matchesSearch = 
      tx.transactionId.toLowerCase().includes(searchTerm.toLowerCase()) ||
      tx.merchantId.toLowerCase().includes(searchTerm.toLowerCase()) ||
      tx.failureCode.toLowerCase().includes(searchTerm.toLowerCase()) ||
      tx.failureMessage.toLowerCase().includes(searchTerm.toLowerCase());
      
    const matchesStatus = statusFilter === 'ALL' || tx.status === statusFilter;
    return matchesSearch && matchesStatus;
  });

  const getStatusBadge = (status: string) => {
    switch (status) {
      case 'RECOVERED':
        return <span className="px-2 py-1 inline-flex text-xs leading-5 font-semibold rounded-full bg-green-100 text-green-800 border border-green-200">✓ Recovered</span>;
      case 'FAILED':
        return <span className="px-2 py-1 inline-flex text-xs leading-5 font-semibold rounded-full bg-gray-100 text-gray-700 border border-gray-200">✗ Failed / Nudged</span>;
      case 'ESCALATED':
        return <span className="px-2 py-1 inline-flex text-xs leading-5 font-semibold rounded-full bg-red-100 text-red-800 border border-red-200">⚠ Escalated</span>;
      case 'PENDING':
        return (
          <span className="px-2 py-1 inline-flex text-xs leading-5 font-semibold rounded-full bg-blue-100 text-blue-800 border border-blue-200 animate-pulse">
            ● Pending Retry
          </span>
        );
      default:
        return <span className="px-2 py-1 inline-flex text-xs leading-5 font-semibold rounded-full bg-yellow-100 text-yellow-800">{status}</span>;
    }
  };

  const getActorIcon = (actor: string) => {
    return actor === 'HUMAN' 
      ? <UserCheck className="w-4 h-4 text-purple-600" />
      : <Activity className="w-4 h-4 text-blue-600" />;
  };

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col font-sans">
      {/* Header */}
      <header className="bg-gray-900 text-white shadow-md">
        <div className="max-w-7xl mx-auto px-4 py-4 sm:px-6 lg:px-8 flex justify-between items-center">
          <div className="flex items-center space-x-3">
            <div className="bg-blue-600 p-2 rounded-lg">
              <ShieldAlert className="w-6 h-6 text-white" />
            </div>
            <div>
              <h1 className="text-xl font-bold tracking-tight">AI Revenue Recovery Agent</h1>
              <p className="text-xs text-gray-400">Razorpay AI Buildathon — Payment Failure Detection & Recovery</p>
            </div>
          </div>
          <button 
            onClick={fetchData} 
            disabled={isRefreshing}
            className="flex items-center space-x-2 bg-gray-800 hover:bg-gray-700 active:bg-gray-900 border border-gray-700 px-4 py-2 rounded-md text-sm font-medium transition duration-150 disabled:opacity-50"
          >
            <RefreshCw className={`w-4 h-4 ${isRefreshing ? 'animate-spin' : ''}`} />
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh Data'}</span>
          </button>
        </div>
      </header>

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 py-6 sm:px-6 lg:px-8 space-y-6">
        {/* KPI metrics */}
        {metrics && (
          <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
            {/* Card 1: Recovery Rate */}
            <div className="bg-white overflow-hidden shadow rounded-lg border border-gray-200">
              <div className="p-5">
                <div className="flex items-center">
                  <div className="flex-shrink-0 bg-green-500 rounded-md p-3">
                    <TrendingUp className="h-6 w-6 text-white" />
                  </div>
                  <div className="ml-5 w-0 flex-1">
                    <dl>
                      <dt className="text-sm font-medium text-gray-500 truncate">Recovery Rate (Soft Failures)</dt>
                      <dd className="flex items-baseline">
                        <div className="text-2xl font-bold text-gray-900">{metrics.recoveryRate.toFixed(1)}%</div>
                      </dd>
                    </dl>
                  </div>
                </div>
              </div>
              <div className="bg-green-50 px-5 py-3 border-t border-green-100 flex justify-between text-xs text-green-700 font-semibold">
                <span>{metrics.recoveredCount} Recovered</span>
                <span>{metrics.totalIngested} Total Ingested</span>
              </div>
            </div>

            {/* Card 2: Revenue Recovered */}
            <div className="bg-white overflow-hidden shadow rounded-lg border border-gray-200">
              <div className="p-5">
                <div className="flex items-center">
                  <div className="flex-shrink-0 bg-blue-500 rounded-md p-3">
                    <DollarSign className="h-6 w-6 text-white" />
                  </div>
                  <div className="ml-5 w-0 flex-1">
                    <dl>
                      <dt className="text-sm font-medium text-gray-500 truncate">Revenue Recovered</dt>
                      <dd className="flex items-baseline">
                        <div className="text-2xl font-bold text-gray-900">₹{metrics.revenueRecovered.toLocaleString('en-IN', { minimumFractionDigits: 2 })}</div>
                      </dd>
                    </dl>
                  </div>
                </div>
              </div>
              <div className="bg-blue-50 px-5 py-3 border-t border-blue-100 text-xs text-blue-700 font-semibold flex justify-between">
                <span>Realized Recovery Gains</span>
                <span>ROI Positive</span>
              </div>
            </div>

            {/* Card 3: False-retry Cost */}
            <div className="bg-white overflow-hidden shadow rounded-lg border border-gray-200">
              <div className="p-5">
                <div className="flex items-center">
                  <div className="flex-shrink-0 bg-red-500 rounded-md p-3">
                    <AlertTriangle className="h-6 w-6 text-white" />
                  </div>
                  <div className="ml-5 w-0 flex-1">
                    <dl>
                      <dt className="text-sm font-medium text-gray-500 truncate">False-Retry Wasted Cost</dt>
                      <dd className="flex items-baseline">
                        <div className="text-2xl font-bold text-gray-900">₹{metrics.falseRetryCost.toFixed(2)}</div>
                      </dd>
                    </dl>
                  </div>
                </div>
              </div>
              <div className="bg-red-50 px-5 py-3 border-t border-red-100 text-xs text-red-700 font-semibold flex justify-between">
                <span>Wasted Fees (Fails)</span>
                <span>Optimized via EV Gate</span>
              </div>
            </div>

            {/* Card 4: Exception Queue Size */}
            <div className="bg-white overflow-hidden shadow rounded-lg border border-gray-200">
              <div className="p-5">
                <div className="flex items-center">
                  <div className="flex-shrink-0 bg-orange-500 rounded-md p-3">
                    <ShieldAlert className="h-6 w-6 text-white" />
                  </div>
                  <div className="ml-5 w-0 flex-1">
                    <dl>
                      <dt className="text-sm font-medium text-gray-500 truncate">Human Review Queue</dt>
                      <dd className="flex items-baseline">
                        <div className="text-2xl font-bold text-gray-900">{metrics.escalatedCount}</div>
                      </dd>
                    </dl>
                  </div>
                </div>
              </div>
              <div className="bg-orange-50 px-5 py-3 border-t border-orange-100 text-xs text-orange-700 font-semibold flex justify-between">
                <span>Pending Human Action</span>
                <span>Above threshold / Low conf</span>
              </div>
            </div>
          </div>
        )}

        {/* Tab Selection */}
        <div className="flex space-x-4 border-b border-gray-200">
          <button
            onClick={() => setActiveTab('ledger')}
            className={`pb-3 px-4 text-sm font-semibold border-b-2 transition duration-150 ${
              activeTab === 'ledger' 
                ? 'border-blue-600 text-blue-600' 
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            <span className="flex items-center space-x-2">
              <FileText className="w-4 h-4" />
              <span>Transaction Ledger ({transactions.length})</span>
            </span>
          </button>
          <button
            onClick={() => setActiveTab('exceptions')}
            className={`pb-3 px-4 text-sm font-semibold border-b-2 transition duration-150 ${
              activeTab === 'exceptions' 
                ? 'border-blue-600 text-blue-600' 
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            <span className="flex items-center space-x-2">
              <ShieldAlert className="w-4 h-4" />
              <span>Exception Review Queue ({metrics?.escalatedCount || 0})</span>
            </span>
          </button>
        </div>

        {/* Tab 1: Transaction Ledger */}
        {activeTab === 'ledger' && (
          <div className="bg-white shadow rounded-lg border border-gray-200 overflow-hidden">
            {/* Search and Filters */}
            <div className="p-4 bg-gray-50 border-b border-gray-200 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <div className="relative flex-1 max-w-md">
                <Search className="absolute left-3 top-2.5 h-4 w-4 text-gray-400" />
                <input
                  type="text"
                  placeholder="Search by Transaction ID, Merchant ID, Code..."
                  value={searchTerm}
                  onChange={e => setSearchTerm(e.target.value)}
                  className="pl-9 pr-4 py-2 border border-gray-300 rounded-md w-full focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 text-sm"
                />
              </div>
              <div className="flex flex-wrap gap-2">
                {['ALL', 'RECOVERED', 'FAILED', 'ESCALATED', 'PENDING'].map(filter => (
                  <button
                    key={filter}
                    onClick={() => setStatusFilter(filter)}
                    className={`px-3 py-1.5 rounded-md text-xs font-semibold border transition duration-150 ${
                      statusFilter === filter
                        ? 'bg-blue-600 border-blue-600 text-white shadow-sm'
                        : 'bg-white border-gray-300 text-gray-700 hover:bg-gray-50'
                    }`}
                  >
                    {filter === 'ALL' ? 'Show All' : filter}
                  </button>
                ))}
              </div>
            </div>

            {/* Table */}
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200 text-left">
                <thead className="bg-gray-50 text-gray-500 text-xs font-bold uppercase tracking-wider">
                  <tr>
                    <th className="px-6 py-3">Transaction ID</th>
                    <th className="px-6 py-3">Timestamp</th>
                    <th className="px-6 py-3">Merchant ID</th>
                    <th className="px-6 py-3">Amount</th>
                    <th className="px-6 py-3">Method</th>
                    <th className="px-6 py-3">Failure Context</th>
                    <th className="px-6 py-3">Retries</th>
                    <th className="px-6 py-3">Status</th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200 text-sm text-gray-900">
                  {filteredTx.length === 0 ? (
                    <tr>
                      <td colSpan={8} className="px-6 py-10 text-center text-gray-400">
                        No transactions found matching filters.
                      </td>
                    </tr>
                  ) : (
                    filteredTx.map(tx => (
                      <tr 
                        key={tx.transactionId} 
                        onClick={() => handleRowClick(tx)}
                        className="hover:bg-blue-50/40 cursor-pointer transition duration-150"
                      >
                        <td className="px-6 py-4 font-mono font-medium text-xs text-blue-600">{tx.transactionId}</td>
                        <td className="px-6 py-4 text-xs text-gray-500">{new Date(tx.createdAt).toLocaleString()}</td>
                        <td className="px-6 py-4 text-xs text-gray-500">{tx.merchantId}</td>
                        <td className="px-6 py-4 font-semibold text-gray-800">₹{tx.amount.toFixed(2)}</td>
                        <td className="px-6 py-4 text-xs font-semibold uppercase text-gray-600">{tx.paymentMethod}</td>
                        <td className="px-6 py-4">
                          <div className="font-semibold text-xs text-red-600">{tx.failureCode}</div>
                          <div className="text-xs text-gray-500 truncate max-w-xs">{tx.failureMessage}</div>
                        </td>
                        <td className="px-6 py-4 font-semibold text-center">{tx.retryCount}</td>
                        <td className="px-6 py-4">{getStatusBadge(tx.status)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}

        {/* Tab 2: Exception Queue */}
        {activeTab === 'exceptions' && (
          <div className="bg-white shadow rounded-lg border border-gray-200 overflow-hidden">
            <div className="p-4 bg-gray-50 border-b border-gray-200">
              <h2 className="text-md font-semibold text-gray-800 flex items-center space-x-2">
                <AlertTriangle className="w-5 h-5 text-orange-500" />
                <span>Requires Human Intervention</span>
              </h2>
              <p className="text-xs text-gray-500 mt-1">
                The following transactions exceed value thresholds, limit attempts, or have low classification confidence.
              </p>
            </div>
            
            <div className="overflow-x-auto">
              <table className="min-w-full divide-y divide-gray-200 text-left">
                <thead className="bg-gray-50 text-gray-500 text-xs font-bold uppercase tracking-wider">
                  <tr>
                    <th className="px-6 py-3">Transaction ID</th>
                    <th className="px-6 py-3">Escalation Date</th>
                    <th className="px-6 py-3">Amount</th>
                    <th className="px-6 py-3">Failure Reason</th>
                    <th className="px-6 py-3">Escalation Trigger</th>
                    <th className="px-6 py-3 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200 text-sm text-gray-900">
                  {transactions.filter(t => t.status === 'ESCALATED').length === 0 ? (
                    <tr>
                      <td colSpan={6} className="px-6 py-10 text-center text-gray-400">
                        Exception queue is empty. Good job!
                      </td>
                    </tr>
                  ) : (
                    transactions.filter(t => t.status === 'ESCALATED').map(tx => {
                      // Work out why it was escalated
                      let trigger = "Rule Exhaustion / Max Retries";
                      if (tx.amount > 50000) trigger = "High Value (₹" + tx.amount.toLocaleString() + " > ₹50,000)";
                      else if (tx.retryCount >= 3) trigger = "Retry Limit Exceeded (3/3)";
                      
                      return (
                        <tr key={tx.transactionId} className="hover:bg-gray-50">
                          <td className="px-6 py-4 font-mono font-medium text-xs text-blue-600" onClick={() => handleRowClick(tx)}>
                            {tx.transactionId}
                          </td>
                          <td className="px-6 py-4 text-xs text-gray-500" onClick={() => handleRowClick(tx)}>
                            {new Date(tx.updatedAt).toLocaleString()}
                          </td>
                          <td className="px-6 py-4 font-semibold text-gray-800" onClick={() => handleRowClick(tx)}>
                            ₹{tx.amount.toFixed(2)}
                          </td>
                          <td className="px-6 py-4" onClick={() => handleRowClick(tx)}>
                            <div className="font-semibold text-xs text-red-600">{tx.failureCode}</div>
                            <div className="text-xs text-gray-500">{tx.failureMessage}</div>
                          </td>
                          <td className="px-6 py-4" onClick={() => handleRowClick(tx)}>
                            <span className="px-2.5 py-1 text-xs font-semibold rounded bg-red-50 text-red-700 border border-red-200">
                              {trigger}
                            </span>
                          </td>
                          <td className="px-6 py-4 text-right space-x-2 whitespace-nowrap">
                            <button
                              onClick={() => handleResolveException(tx.transactionId, 'FORCE_RETRY')}
                              disabled={actionLoading === tx.transactionId}
                              className="bg-green-600 hover:bg-green-700 active:bg-green-800 disabled:opacity-50 text-white px-3 py-1.5 rounded-md text-xs font-bold transition shadow-sm"
                            >
                              {actionLoading === tx.transactionId ? 'Retrying...' : 'Force Retry'}
                            </button>
                            <button
                              onClick={() => handleResolveException(tx.transactionId, 'ACCEPT_LOSS')}
                              disabled={actionLoading === tx.transactionId}
                              className="bg-gray-100 hover:bg-gray-200 active:bg-gray-300 border border-gray-300 disabled:opacity-50 text-gray-700 px-3 py-1.5 rounded-md text-xs font-bold transition"
                            >
                              {actionLoading === tx.transactionId ? 'Resolving...' : 'Accept Loss'}
                            </button>
                          </td>
                        </tr>
                      );
                    })
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </main>

      {/* Audit Timeline Modal */}
      {selectedTx && (
        <div className="fixed inset-0 bg-black bg-opacity-50 z-50 flex items-center justify-center p-4">
          <div className="bg-white rounded-lg shadow-xl max-w-3xl w-full max-h-[85vh] overflow-hidden flex flex-col border border-gray-200">
            {/* Modal Header */}
            <div className="bg-gray-900 text-white px-6 py-4 flex justify-between items-center">
              <div>
                <h3 className="text-md font-bold">Audit Trail & Diagnosis</h3>
                <p className="text-xs text-gray-400 font-mono mt-0.5">Transaction ID: {selectedTx.transactionId}</p>
              </div>
              <button 
                onClick={() => { setSelectedTxId(null); setSelectedTx(null); }}
                className="text-gray-400 hover:text-white transition text-lg font-bold"
              >
                ✕
              </button>
            </div>

            {/* Modal Content */}
            <div className="p-6 overflow-y-auto space-y-6 flex-1">
              {/* Transaction Metadata summary */}
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 p-4 bg-gray-50 border border-gray-200 rounded-lg text-xs">
                <div>
                  <span className="text-gray-400 block font-medium uppercase tracking-wide">Amount</span>
                  <span className="font-bold text-sm text-gray-800">₹{selectedTx.amount.toFixed(2)}</span>
                </div>
                <div>
                  <span className="text-gray-400 block font-medium uppercase tracking-wide">Method</span>
                  <span className="font-semibold text-gray-800 uppercase">{selectedTx.paymentMethod}</span>
                </div>
                <div>
                  <span className="text-gray-400 block font-medium uppercase tracking-wide">Attempts</span>
                  <span className="font-semibold text-gray-800">{selectedTx.retryCount} / 3</span>
                </div>
                <div>
                  <span className="text-gray-400 block font-medium uppercase tracking-wide">Status</span>
                  <div>{getStatusBadge(selectedTx.status)}</div>
                </div>
              </div>

              {/* Recovery Actions list if any */}
              {recoveryActions.length > 0 && (
                <div>
                  <h4 className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-2">Intervention Actions</h4>
                  <div className="space-y-2">
                    {recoveryActions.map(action => (
                      <div key={action.id} className="border border-gray-200 rounded-md p-3 text-xs flex justify-between items-center bg-gray-50/50">
                        <div>
                          <div className="font-semibold text-gray-800 flex items-center space-x-1.5">
                            <Clock className="w-3.5 h-3.5 text-gray-500" />
                            <span className="uppercase text-blue-600">{action.actionTaken.replace('_', ' ')}</span>
                            {action.retryAttemptNo > 0 && <span className="text-gray-500">(Attempt #{action.retryAttemptNo})</span>}
                          </div>
                          {action.nudgeMessage && (
                            <div className="mt-1 text-gray-600 italic bg-white border border-gray-100 p-2 rounded">
                              💬 Nudge: "{action.nudgeMessage}"
                            </div>
                          )}
                          <div className="text-gray-400 mt-0.5">{new Date(action.executedAt).toLocaleString()}</div>
                        </div>
                        <div className="text-right">
                          <span className={`px-2 py-0.5 rounded font-semibold ${
                            action.outcome === 'SUCCESS' ? 'bg-green-50 text-green-700 border border-green-200' :
                            action.outcome === 'FAILED' ? 'bg-red-50 text-red-700 border border-red-200' :
                            'bg-blue-50 text-blue-700 border border-blue-200'
                          }`}>
                            {action.outcome}
                          </span>
                          <div className="text-gray-400 mt-1">Cost: ₹{action.costOfAttempt.toFixed(2)}</div>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* Chronological Audit logs */}
              <div>
                <h4 className="text-xs font-bold text-gray-400 uppercase tracking-wider mb-4">Chronological Timeline</h4>
                <div className="flow-root">
                  <ul className="-mb-8">
                    {auditLogs.map((log, logIdx) => (
                      <li key={log.id}>
                        <div className="relative pb-8">
                          {logIdx !== auditLogs.length - 1 ? (
                            <span className="absolute top-4 left-4 -ml-px h-full w-0.5 bg-gray-200" aria-hidden="true" />
                          ) : null}
                          <div className="relative flex space-x-3">
                            <div>
                              <span className="h-8 w-8 rounded-full bg-gray-100 border border-gray-300 flex items-center justify-center">
                                {getActorIcon(log.actor)}
                              </span>
                            </div>
                            <div className="flex-1 min-w-0 pt-1.5 flex justify-between space-x-4">
                              <div>
                                <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider flex items-center space-x-1">
                                  <span>{log.step.replace('_', ' ')}</span>
                                  <span className="text-[10px] lowercase text-gray-400 font-normal">({log.actor})</span>
                                </p>
                                <p className="text-xs text-gray-800 mt-1 font-medium bg-gray-50 p-2.5 rounded border border-gray-100 leading-relaxed">
                                  {log.detail}
                                </p>
                              </div>
                              <div className="text-right text-[10px] whitespace-nowrap text-gray-400">
                                {new Date(log.timestamp).toLocaleTimeString()}
                              </div>
                            </div>
                          </div>
                        </div>
                      </li>
                    ))}
                  </ul>
                </div>
              </div>
            </div>
            
            {/* Exception overrides direct action inside modal if escalated */}
            {selectedTx.status === 'ESCALATED' && (
              <div className="p-4 bg-red-50 border-t border-red-100 flex items-center justify-between">
                <span className="text-xs text-red-800 font-semibold flex items-center space-x-1.5">
                  <ShieldAlert className="w-4 h-4 text-red-700" />
                  <span>Human action required:</span>
                </span>
                <div className="space-x-2">
                  <button
                    onClick={() => handleResolveException(selectedTx.transactionId, 'FORCE_RETRY')}
                    disabled={actionLoading === selectedTx.transactionId}
                    className="bg-green-600 hover:bg-green-700 active:bg-green-800 text-white px-4 py-1.5 rounded-md text-xs font-bold shadow-sm"
                  >
                    Force Retry
                  </button>
                  <button
                    onClick={() => handleResolveException(selectedTx.transactionId, 'ACCEPT_LOSS')}
                    disabled={actionLoading === selectedTx.transactionId}
                    className="bg-gray-200 hover:bg-gray-300 text-gray-700 px-4 py-1.5 rounded-md text-xs font-bold"
                  >
                    Accept Loss
                  </button>
                </div>
              </div>
            )}

            {/* Modal Footer */}
            <div className="bg-gray-50 px-6 py-3 border-t border-gray-200 flex justify-end">
              <button 
                onClick={() => { setSelectedTxId(null); setSelectedTx(null); }}
                className="bg-white hover:bg-gray-100 border border-gray-300 px-4 py-2 rounded-md text-xs font-bold text-gray-700 transition"
              >
                Close Diagnosis
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Footer */}
      <footer className="bg-white border-t border-gray-200 py-4 text-center text-xs text-gray-400">
        AI Revenue Recovery Dashboard — Razorpay AI Buildathon 2026. Made with ❤️.
      </footer>
    </div>
  );
}
