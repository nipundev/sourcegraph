import { CodeIntelligenceBadge } from './CodeIntelligenceBadge'

/**
 * Common props for components needing to decide whether to show Code intelligence
 */
export interface CodeIntelligenceProps {
    codeIntelligenceEnabled: boolean
    codeIntelligenceBadge?: typeof CodeIntelligenceBadge
}
