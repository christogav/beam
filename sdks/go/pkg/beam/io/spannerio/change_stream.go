package spannerio

import (
	"cloud.google.com/go/spanner"
	"context"
	"fmt"
	"google.golang.org/api/iterator"
	"time"
)

type ChangeStreamRestriction struct {
	PartitionToken string
	Start          time.Time
	End            time.Time
}

type ChangeStreamFn struct {
	spannerFn
}

// CreateInitialRestriction generates restrictions based on initial partition tokens.
func (fn *ChangeStreamFn) CreateInitialRestriction(ctx context.Context, _ string) []*ChangeStreamRestriction {
	partitionTokens := fn.fetchPartitionTokens(ctx)
	var restrictions []*ChangeStreamRestriction
	for _, token := range partitionTokens {
		restrictions = append(restrictions, &ChangeStreamRestriction{
			PartitionToken: token,
			Start:          time.Now().Add(-time.Hour), // Adjust start time as needed
			End:            time.Now(),
		})
	}
	return restrictions
}

// fetchPartitionTokens queries Spanner for initial partition tokens.
func (fn *ChangeStreamFn) fetchPartitionTokens(ctx context.Context) []string {
	stmt := spanner.NewStatement("SELECT partition_token FROM information_schema.change_stream_partitions")
	iter := fn.client.Single().Query(ctx, stmt)
	defer iter.Stop()

	var tokens []string
	for {
		row, err := iter.Next()
		if err != nil {
			if err == iterator.Done {
				break
			}
			panic(fmt.Sprintf("failed to fetch partition tokens: %v", err))
		}

		var token string
		if err := row.Column(0, &token); err != nil {
			panic("error reading partition token: " + err.Error())
		}
		tokens = append(tokens, token)
	}
	return tokens
}

// SplitRestriction splits the restriction into smaller intervals.
func (fn *ChangeStreamFn) SplitRestriction(restriction *ChangeStreamRestriction, desiredNumSplits int) []*ChangeStreamRestriction {
	duration := restriction.End.Sub(restriction.Start)
	interval := duration / time.Duration(desiredNumSplits)

	var splits []*ChangeStreamRestriction
	for i := 0; i < desiredNumSplits; i++ {
		splitStart := restriction.Start.Add(time.Duration(i) * interval)
		splitEnd := splitStart.Add(interval)
		splits = append(splits, &ChangeStreamRestriction{
			PartitionToken: restriction.PartitionToken,
			Start:          splitStart,
			End:            splitEnd,
		})
	}
	return splits
}

// RestrictionSize returns the size of the restriction based on duration.
func (fn *ChangeStreamFn) RestrictionSize(restriction *ChangeStreamRestriction) float64 {
	return restriction.End.Sub(restriction.Start).Seconds()
}

// ProcessElement processes a restriction and emits change stream records.
func (fn *ChangeStreamFn) ProcessElement(ctx context.Context, element string, restriction *ChangeStreamRestriction, emit func(string)) error {
	query := spanner.NewStatement("SELECT * FROM READ_CHANGE_STREAMS(@partition_token, @start_time, @end_time)")
	query.Params["partition_token"] = restriction.PartitionToken
	query.Params["start_time"] = restriction.Start
	query.Params["end_time"] = restriction.End

	it := fn.client.Single().Query(ctx, query)
	defer it.Stop()

	for {
		row, err := it.Next()
		if err != nil {
			if err == iterator.Done {
				break
			}
			return err
		}

		var record string // Replace with your actual schema
		if err := row.ColumnByName("change_record", &record); err != nil {
			return err
		}

		emit(record)
	}

	return nil
}
