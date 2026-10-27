import pandas as pd
import numpy as np

def map_value(val, thresholds, scores):
    """Interpolates a value between threshold points to a score scale."""
    return np.interp(val, thresholds, scores)

def compute_hsrs():
    """
    Computes the Heat Stroke Risk Score (HSRS).
    
    The HSRS is an adaptation and extension of the Physiological Strain Index (PSI) 
    (Moran, Shitzer & Pandolf 1998), which originally used only Core Temperature (CT) 
    and Heart Rate (HR). Our rubric extends PSI by incorporating ambient Heat Index (HI) 
    as a third component (weighted at 20%), to capture environmental thermal load.
    
    Clinical Anchors:
    - Moran, Shitzer & Pandolf 1998 (PSI): Primary physiological strain index.
    - Tikuisis, McLellan & Selkirk 2002: Validation of PSI in applied settings.
    - Bouchama & Knochel 2002 (NEJM): Clinical reference for the 40.0 C heatstroke threshold.
    - ACSM: Exertion zone conventions for Heart Rate thresholds (100/130 bpm).
    """
    file_path = "data/(v6.3)synthetic_scenarios_dataset_may_to_sep.csv"
    print(f"Reading dataset from {file_path}...")
    df = pd.read_csv(file_path)

    # 1. Define Thresholds and Corresponding Scores (0-10)
    
    # Core Temp thresholds (Celsius)
    ct_thresholds = [36.5, 37.5, 38.5, 40.0, 42.0]
    ct_scores     = [0,    2,    6,    9,    10]
    
    # Heart Rate thresholds (bpm)
    hr_thresholds = [60,   100,  130,  160,  200]
    hr_scores     = [0,    2,    5,    8,    10]
    
    # Heat Index thresholds (Celsius)
    # Recalibrated to Kuwait climate: peak HI ~51 °C maps to ~9.5.
    # Old ceiling of 60 °C was never reachable; 54 °C is the new ceiling.
    hi_thresholds = [20,   32,   41,   48,   54]
    hi_scores     = [0,    2,    5,    9,    10]

    print("Calculating individual HSRS components...")
    update_mask = df['Final_Three_HSRS'].isna() | df['Final_Three_HSRS'].eq(0)
    
    # Calculate Individual Component Scores
    df.loc[update_mask, 'CT_HSRS'] = df.loc[update_mask, 'CT'].apply(
        lambda x: map_value(x, ct_thresholds, ct_scores))
    df.loc[update_mask, 'HR_HSRS'] = df.loc[update_mask, 'hr'].apply(
        lambda x: map_value(x, hr_thresholds, hr_scores))
    df.loc[update_mask, 'HI_HSRS'] = df.loc[update_mask, 'HI_Rothfusz'].apply(
        lambda x: map_value(x, hi_thresholds, hi_scores))

    print("Calculating final composite scores...")
    
    # Final_Two_HSRS: Environmental + Exertion (Average of HR and HI)
    df.loc[update_mask, 'Final_Two_HSRS'] = (
        df.loc[update_mask, 'HR_HSRS'] + df.loc[update_mask, 'HI_HSRS']) / 2
    
    # Final_Three_HSRS: Comprehensive Risk (Weighted favoring Core Temp)
    # 50% CT, 30% HR, 20% HI
    df.loc[update_mask, 'Final_Three_HSRS'] = (
        (0.5 * df.loc[update_mask, 'CT_HSRS'])
        + (0.3 * df.loc[update_mask, 'HR_HSRS'])
        + (0.2 * df.loc[update_mask, 'HI_HSRS'])
    )
    print(f"Updated {update_mask.sum():,} rows; preserved {len(df) - update_mask.sum():,}.")

    # Round scores for readability
    score_cols = ['CT_HSRS', 'HR_HSRS', 'HI_HSRS', 'Final_Two_HSRS', 'Final_Three_HSRS']
    for col in score_cols:
        df.loc[update_mask, col] = df.loc[update_mask, col].round(2)

    print(f"Saving updated dataset to {file_path}...")
    df.to_csv(file_path, index=False)
    
    # Summary of Risk levels
    print("\nHeat Stroke Risk Score (HSRS) Summary:")
    print(f"Mean Final Risk: {df['Final_Three_HSRS'].mean():.2f}")
    print(f"Max Final Risk:  {df['Final_Three_HSRS'].max():.2f}")
    
    # Count records above threshold 7 (High Risk)
    high_risk_count = len(df[df['Final_Three_HSRS'] >= 7.0])
    print(f"Records in High Risk category (>= 7.0): {high_risk_count}")

    print("\nDone. HSRS columns updated.")

if __name__ == "__main__":
    compute_hsrs()
